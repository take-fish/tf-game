package com.fishmatrix.game.service;

import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.converter.MatchConverters;
import com.fishmatrix.game.converter.SqlTime;
import com.fishmatrix.game.converter.TournamentConverters;
import com.fishmatrix.game.mapper.MatchMapper;
import com.fishmatrix.game.mapper.PlayerStatMapper;
import com.fishmatrix.game.mapper.TournamentMapper;
import com.fishmatrix.game.model.MatchFinishedEvent;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.TournamentPlayerRecord;
import com.fishmatrix.game.model.TournamentRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 赛程编排与结算：邀请齐备后开赛、轮次推进、对阵激活、瑞士轮生成与冠亚军结算。
 */
@Slf4j
@RequiredArgsConstructor
@Service
class TournamentBracketService {

    private final TournamentMapper tournaments;
    private final MatchMapper matches;
    private final PlayerStatMapper stats;
    private final GameNotificationService notifications;
    private final GameProperties properties;
    private final TournamentBroadcaster broadcaster;

    /** 所有邀请已响应后开赛；不足 2 人取消。 */
    @Transactional
    void startIfReady(TournamentRecord tournament) {
        if (!"INVITING".equals(tournament.status())) {
            return;
        }
        List<TournamentPlayerRecord> waiting = TournamentConverters.playerRecords(
                tournaments.selectAcceptedOrInvited(tournament.id()));
        if (waiting.stream().anyMatch(player -> "INVITED".equals(player.status()))) {
            return;
        }
        List<TournamentPlayerRecord> accepted = acceptedPlayers(tournament.id());
        if (accepted.size() < properties.minPlayers()) {
            tournaments.cancel(tournament.id(),
                    SqlTime.toDateTime(System.currentTimeMillis()));
            TournamentRecord cancelled = findTournament(tournament.id()).orElse(tournament);
            broadcaster.announceCancelled(cancelled);
            return;
        }

        int seed = 1;
        for (TournamentPlayerRecord player : accepted) {
            tournaments.updateSeed(tournament.id(), player.userId(), seed);
            seed += 1;
        }
        accepted = acceptedPlayers(tournament.id());
        int count = accepted.size();
        long now = System.currentTimeMillis();

        List<MatchRecord> planned;
        String bracketType;
        int totalRounds;
        if (BracketPlanner.isPowerOfTwo(count)) {
            bracketType = BracketPlanner.STAGE_KNOCKOUT;
            totalRounds = BracketPlanner.knockoutRounds(count);
            planned = BracketPlanner.planKnockout(tournament.id(), accepted, now);
        } else {
            bracketType = BracketPlanner.STAGE_LEAGUE;
            int leagueRounds = BracketPlanner.leagueRounds(count);
            totalRounds = leagueRounds + BracketPlanner.pagePlayoffRounds(Math.min(4, count));
            planned = count <= 5
                    ? BracketPlanner.planRoundRobin(tournament.id(), accepted, now)
                    : BracketPlanner.planSwissFirstRound(tournament.id(), accepted, now);
        }

        if (tournaments.start(tournament.id(), bracketType, totalRounds,
                SqlTime.toDateTime(now)) == 0) {
            return;
        }
        for (MatchRecord match : planned) {
            matches.insert(MatchConverters.toEntity(match));
            ensureFirstGame(tournament, match);
        }
        TournamentRecord started = findTournament(tournament.id()).orElse(tournament);
        if (TournamentService.KIND_DUEL.equals(started.kind())) {
            broadcaster.broadcastDuelStarted(started);
        } else {
            broadcaster.broadcastTournament(started);
        }
        log.info("{} {} 开赛：{} 人，{}，{} 轮",
                TournamentService.KIND_DUEL.equals(started.kind()) ? "约战" : "赛事",
                started.id(), count, bracketType, totalRounds);
    }

    @Transactional
    void onMatchFinished(MatchFinishedEvent event) {
        MatchRecord match = findMatch(event.matchId()).orElse(null);
        if (match == null) {
            return;
        }
        TournamentRecord tournament = findTournament(match.tournamentId()).orElse(null);
        if (tournament == null || !"RUNNING".equals(tournament.status())) {
            return;
        }
        boolean finalMatch = TournamentRules.isFinalMatch(tournament, match);

        if (TournamentService.KIND_TOURNAMENT.equals(tournament.kind())
                && !BracketPlanner.STATUS_BYE.equals(match.status())
                && match.winnerUserId() != null) {
            String actualGameId = match.firstGameId() == null
                    ? tournament.gameId() : match.firstGameId();
            recordLeaderboard(actualGameId, match, finalMatch);
        }

        if (match.nextMatchId() != null && match.winnerUserId() != null) {
            boolean slot1 = BracketPlanner.STAGE_KNOCKOUT.equals(match.stage())
                    && BracketPlanner.nextSlotIsPlayer1(match.slotNo());
            String winnerNickname = match.winnerUserId().equals(match.player1UserId())
                    ? match.player1Nickname() : match.player2Nickname();
            matches.fillNextSlot(match.nextMatchId(), match.winnerUserId(), winnerNickname, slot1);
            Optional.ofNullable(matches.selectById(match.nextMatchId()))
                    .map(MatchConverters::toRecord)
                    .ifPresent(next -> {
                        if (BracketPlanner.STATUS_PENDING.equals(next.status())
                                && next.player1UserId() != null
                                && next.player2UserId() != null) {
                            matches.updateStatus(next.id(), BracketPlanner.STATUS_READY);
                        }
                    });
        }

        if (finalMatch) {
            finishTournament(tournament, match);
            return;
        }

        if (BracketPlanner.STAGE_LEAGUE.equals(match.stage())) {
            advanceLeague(tournament);
        } else {
            activatePendingMatches(tournament);
        }
        broadcaster.broadcastTournament(
                findTournament(tournament.id()).orElse(tournament));
    }

    /** 编排补偿：多场比赛几乎同时结束时，事件监听可能都看不到对方未提交的数据。 */
    @Transactional
    void tickProgress() {
        for (TournamentRecord tournament : TournamentConverters.records(
                tournaments.selectRunning())) {
            try {
                if (BracketPlanner.STAGE_LEAGUE.equals(tournament.bracketType())) {
                    advanceLeague(tournament);
                } else {
                    activatePendingMatches(tournament);
                }
            } catch (Exception exception) {
                log.warn("推进赛事失败 tournament={}", tournament.id(), exception);
            }
        }
    }

    /** 积分赛一轮全部结束后：生成/激活下一轮，或取前四进入页面淘汰赛。 */
    @Transactional
    void advanceLeague(TournamentRecord tournament) {
        if (!"RUNNING".equals(tournament.status())
                || !BracketPlanner.STAGE_LEAGUE.equals(tournament.bracketType())) {
            return;
        }
        int current = tournament.currentRound();
        int total = matches.countRoundMatches(tournament.id(), BracketPlanner.STAGE_LEAGUE, current);
        int finished = matches.countRoundFinished(
                tournament.id(), BracketPlanner.STAGE_LEAGUE, current);
        if (total == 0 || finished < total) {
            return;
        }
        int qualifierCount = Math.min(4, acceptedPlayers(tournament.id()).size());
        int leagueRounds = Math.max(1, tournament.totalRounds()
                - BracketPlanner.pagePlayoffRounds(qualifierCount));

        if (current < leagueRounds) {
            int nextRound = current + 1;
            if (tournaments.advanceRound(tournament.id(), current, nextRound) == 0) {
                return;
            }
            List<TournamentPlayerRecord> players = acceptedPlayers(tournament.id());
            if (players.size() >= 6) {
                generateSwissRound(tournament, players, nextRound);
            } else {
                matches.activateRound(tournament.id(), BracketPlanner.STAGE_LEAGUE, nextRound);
            }
            broadcaster.broadcastTournament(
                    findTournament(tournament.id()).orElse(tournament));
            return;
        }

        List<TournamentPlayerRecord> qualifiers = standings(tournament.id()).stream()
                .limit(qualifierCount)
                .map(Standing::player)
                .toList();
        if (qualifiers.size() < 3) {
            finishWithoutLadder(tournament, qualifiers);
            return;
        }
        int firstRound = current + 1;
        int totalRounds = current + BracketPlanner.pagePlayoffRounds(qualifiers.size());
        if (tournaments.startPagePlayoff(
                tournament.id(), totalRounds, firstRound, current) == 0) {
            return;
        }
        for (MatchRecord match : BracketPlanner.planPagePlayoff(
                tournament.id(), qualifiers, firstRound, System.currentTimeMillis())) {
            matches.insert(MatchConverters.toEntity(match));
            ensureFirstGame(tournament, match);
        }
        TournamentRecord pagePlayoff = findTournament(tournament.id()).orElse(tournament);
        broadcaster.broadcastTournament(pagePlayoff);
        log.info("赛事 {} 进入页面淘汰赛：{} / {} / {} / {}",
                pagePlayoff.id(), qualifiers.get(0).nickname(),
                qualifiers.size() > 1 ? qualifiers.get(1).nickname() : "-",
                qualifiers.size() > 2 ? qualifiers.get(2).nickname() : "-",
                qualifiers.size() > 3 ? qualifiers.get(3).nickname() : "-");
    }

    /** 把已具备双方、仍为 PENDING 的淘汰/页面淘汰对阵激活；并同步当前轮次展示。 */
    @Transactional
    void activatePendingMatches(TournamentRecord tournament) {
        List<MatchRecord> all = MatchConverters.records(
                matches.selectByTournament(tournament.id()));
        boolean changed = false;
        for (MatchRecord match : all) {
            if (BracketPlanner.STATUS_BYE.equals(match.status())
                    || "FINISHED".equals(match.status())) {
                continue;
            }
            if (match.firstGameId() == null) {
                ensureFirstGame(tournament, match);
                changed = true;
            }
            if (BracketPlanner.STATUS_PENDING.equals(match.status())
                    && match.player1UserId() != null && match.player2UserId() != null) {
                matches.updateStatus(match.id(), BracketPlanner.STATUS_READY);
                changed = true;
            }
        }
        if (BracketPlanner.STAGE_KNOCKOUT.equals(tournament.bracketType())
                || BracketPlanner.STAGE_PAGE_PLAYOFF.equals(tournament.bracketType())) {
            int activeRound = all.stream()
                    .filter(match -> !BracketPlanner.STATUS_BYE.equals(match.status())
                            && !"FINISHED".equals(match.status()))
                    .mapToInt(MatchRecord::roundNo)
                    .min()
                    .orElse(tournament.totalRounds());
            if (activeRound != tournament.currentRound()) {
                tournaments.updateCurrentRound(tournament.id(), activeRound);
                changed = true;
            }
        }
        if (changed) {
            broadcaster.broadcastTournament(
                    findTournament(tournament.id()).orElse(tournament));
        }
    }

    private void recordLeaderboard(String gameId, MatchRecord match, boolean champion) {
        String winnerId = match.winnerUserId();
        boolean winnerIsPlayer1 = winnerId.equals(match.player1UserId());
        String winnerNickname = winnerIsPlayer1 ? match.player1Nickname() : match.player2Nickname();
        String loserId = winnerIsPlayer1 ? match.player2UserId() : match.player1UserId();
        String loserNickname = winnerIsPlayer1 ? match.player2Nickname() : match.player1Nickname();
        stats.addMatch(winnerId, winnerNickname, 1, 0);
        stats.addGameMatch(gameId, winnerId, winnerNickname, 1, 0,
                PlayerStatRules.gameMatchPoints(true, champion), champion ? 1 : 0);
        if (loserId != null) {
            stats.addMatch(loserId, loserNickname, 0, 1);
            stats.addGameMatch(gameId, loserId, loserNickname, 0, 1,
                    PlayerStatRules.gameMatchPoints(false, false), 0);
        }
    }

    private void finishTournament(TournamentRecord tournament, MatchRecord finalMatch) {
        String championId = finalMatch.winnerUserId();
        if (championId == null) {
            championId = finalMatch.player1UserId();
        }
        boolean championIsPlayer1 = championId != null
                && championId.equals(finalMatch.player1UserId());
        String championNickname = championIsPlayer1
                ? finalMatch.player1Nickname() : finalMatch.player2Nickname();
        String runnerUpId = championIsPlayer1
                ? finalMatch.player2UserId() : finalMatch.player1UserId();
        String runnerUpNickname = championIsPlayer1
                ? finalMatch.player2Nickname() : finalMatch.player1Nickname();
        long now = System.currentTimeMillis();
        tournaments.finish(tournament.id(), championId, championNickname,
                runnerUpId, runnerUpNickname, SqlTime.toDateTime(now));
        if (TournamentService.KIND_TOURNAMENT.equals(tournament.kind())) {
            if (championId != null) {
                stats.addTitle(championId, championNickname, 1, 0, PlayerStatRules.titlePoints(true));
            }
            if (runnerUpId != null) {
                stats.addTitle(runnerUpId, runnerUpNickname, 0, 1, PlayerStatRules.titlePoints(false));
            }
        }
        TournamentRecord finished = findTournament(tournament.id()).orElse(tournament);
        if (TournamentService.KIND_TOURNAMENT.equals(finished.kind())) {
            notifications.publishChampion(finished);
        }
        broadcaster.broadcastTournament(finished);
        log.info("{} {} 结束，冠军 {}（{}），亚军 {}（{}）",
                TournamentService.KIND_DUEL.equals(finished.kind()) ? "约战" : "赛事",
                finished.id(), championNickname, championId, runnerUpNickname, runnerUpId);
    }

    private void finishWithoutLadder(TournamentRecord tournament,
                                     List<TournamentPlayerRecord> ranked) {
        if (ranked.isEmpty()) {
            tournaments.cancel(tournament.id(),
                    SqlTime.toDateTime(System.currentTimeMillis()));
            broadcaster.broadcastTournament(tournament);
            return;
        }
        TournamentPlayerRecord champion = ranked.get(0);
        TournamentPlayerRecord runnerUp = ranked.size() > 1 ? ranked.get(1) : null;
        long now = System.currentTimeMillis();
        tournaments.finish(tournament.id(), champion.userId(), champion.nickname(),
                runnerUp == null ? null : runnerUp.userId(),
                runnerUp == null ? null : runnerUp.nickname(), SqlTime.toDateTime(now));
        if (TournamentService.KIND_TOURNAMENT.equals(tournament.kind())) {
            stats.addTitle(champion.userId(), champion.nickname(), 1, 0, PlayerStatRules.titlePoints(true));
            if (runnerUp != null) {
                stats.addTitle(runnerUp.userId(), runnerUp.nickname(), 0, 1, PlayerStatRules.titlePoints(false));
            }
        }
        TournamentRecord finished = findTournament(tournament.id()).orElse(tournament);
        if (TournamentService.KIND_TOURNAMENT.equals(finished.kind())) {
            notifications.publishChampion(finished);
        }
        broadcaster.broadcastTournament(finished);
    }

    private List<Standing> standings(String tournamentId) {
        Map<String, int[]> counters = new HashMap<>();
        List<TournamentPlayerRecord> players = acceptedPlayers(tournamentId);
        for (TournamentPlayerRecord player : players) {
            counters.put(player.userId(), new int[]{0, 0});
        }
        for (MatchRecord match : MatchConverters.records(
                matches.selectByStage(tournamentId, BracketPlanner.STAGE_LEAGUE))) {
            boolean decided = BracketPlanner.STATUS_BYE.equals(match.status())
                    || "FINISHED".equals(match.status());
            if (!decided || match.winnerUserId() == null) {
                continue;
            }
            int[] wins = counters.get(match.winnerUserId());
            if (wins != null) {
                wins[0] += 3;
                wins[1] += 1;
            }
        }
        return players.stream()
                .map(player -> {
                    int[] value = counters.getOrDefault(player.userId(), new int[]{0, 0});
                    return new Standing(player, value[0], value[1]);
                })
                .sorted(Comparator.comparingInt((Standing value) -> value.points()).reversed()
                        .thenComparing(Comparator.comparingInt(
                                (Standing value) -> value.wins()).reversed())
                        .thenComparingInt(value -> value.player().seed()))
                .toList();
    }

    /** 瑞士轮下一轮：按积分排序，尽量避开已经交手过的对手；奇数人数最低分轮空。 */
    private void generateSwissRound(TournamentRecord tournament,
                                    List<TournamentPlayerRecord> players, int roundNo) {
        List<Standing> ordered = standings(tournament.id());
        List<TournamentPlayerRecord> remaining = new ArrayList<>(
                ordered.stream().map(Standing::player).toList());
        if (remaining.isEmpty()) {
            return;
        }
        List<MatchRecord> played = MatchConverters.records(
                matches.selectByStage(tournament.id(), BracketPlanner.STAGE_LEAGUE));
        Set<String> playedPairs = new HashSet<>();
        Set<String> byeIds = new HashSet<>();
        for (MatchRecord match : played) {
            if (BracketPlanner.STATUS_BYE.equals(match.status()) && match.winnerUserId() != null) {
                byeIds.add(match.winnerUserId());
            } else if (match.player1UserId() != null && match.player2UserId() != null) {
                playedPairs.add(TournamentRules.pairKey(
                        match.player1UserId(), match.player2UserId()));
            }
        }

        List<MatchRecord> planned = new ArrayList<>();
        int slot = 1;
        if (remaining.size() % 2 == 1) {
            TournamentPlayerRecord byePlayer = remaining.stream()
                    .filter(player -> !byeIds.contains(player.userId()))
                    .reduce((first, second) -> second)
                    .orElse(remaining.get(remaining.size() - 1));
            remaining.remove(byePlayer);
            planned.add(BracketPlanner.bye(
                    tournament.id(), roundNo, slot++, byePlayer, System.currentTimeMillis()));
        }

        Set<String> paired = new HashSet<>();
        for (int i = 0; i < remaining.size(); i += 1) {
            TournamentPlayerRecord first = remaining.get(i);
            if (paired.contains(first.userId())) {
                continue;
            }
            int partnerIndex = -1;
            for (int j = i + 1; j < remaining.size(); j += 1) {
                TournamentPlayerRecord candidate = remaining.get(j);
                if (!paired.contains(candidate.userId())
                        && !playedPairs.contains(TournamentRules.pairKey(
                                first.userId(), candidate.userId()))) {
                    partnerIndex = j;
                    break;
                }
            }
            if (partnerIndex < 0) {
                for (int j = i + 1; j < remaining.size(); j += 1) {
                    if (!paired.contains(remaining.get(j).userId())) {
                        partnerIndex = j;
                        break;
                    }
                }
            }
            if (partnerIndex < 0) {
                continue;
            }
            TournamentPlayerRecord second = remaining.get(partnerIndex);
            paired.add(first.userId());
            paired.add(second.userId());
            planned.add(new MatchRecord(
                    UUID.randomUUID().toString(), tournament.id(), roundNo, slot++,
                    BracketPlanner.STAGE_LEAGUE, BracketPlanner.STATUS_READY,
                    first.userId(), first.nickname(), second.userId(), second.nickname(),
                    0, 0, null, null, System.currentTimeMillis(), null, null));
        }
        for (MatchRecord match : planned) {
            matches.insert(MatchConverters.toEntity(match));
        }
    }

    private void ensureFirstGame(TournamentRecord tournament, MatchRecord match) {
        if (match.firstGameId() != null || BracketPlanner.STATUS_BYE.equals(match.status())) {
            return;
        }
        matches.initFirstGame(match.id(), TournamentRules.resolveGame(tournament),
                match.gameChooserUserId());
    }

    private List<TournamentPlayerRecord> acceptedPlayers(String tournamentId) {
        return TournamentConverters.playerRecords(
                tournaments.selectAcceptedPlayers(tournamentId));
    }

    private Optional<TournamentRecord> findTournament(String id) {
        return Optional.ofNullable(tournaments.selectActiveById(id))
                .map(TournamentConverters::toRecord);
    }

    private Optional<MatchRecord> findMatch(String id) {
        return Optional.ofNullable(matches.selectById(id))
                .map(MatchConverters::toRecord);
    }

    private record Standing(TournamentPlayerRecord player, int points, int wins) {
    }
}
