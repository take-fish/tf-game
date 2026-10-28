package com.fishmatrix.game.service;

import com.fishmatrix.game.arena.ArenaGames;
import com.fishmatrix.game.arena.ArenaService;
import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.converter.MatchConverters;
import com.fishmatrix.game.converter.SqlTime;
import com.fishmatrix.game.converter.TournamentConverters;
import com.fishmatrix.game.mapper.MatchMapper;
import com.fishmatrix.game.mapper.TournamentMapper;
import com.fishmatrix.game.model.GameIds;
import com.fishmatrix.game.model.MatchFinishedEvent;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.RoundRecord;
import com.fishmatrix.game.model.TournamentPlayerRecord;
import com.fishmatrix.game.model.TournamentRecord;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 小局与比赛的结算：条件更新保证并发下只结算一次，并统一走 BO 计分、
 * 休息与晋级流程（含竞技场、巡检对抗的对局收尾）。
 */
@RequiredArgsConstructor
@Service
class MatchSettlementService {

    private final MatchMapper matches;
    private final TournamentMapper tournaments;
    private final ApplicationEventPublisher publisher;
    private final GameProperties properties;
    private final ArenaService arena;
    private final ExerciseService exercise;
    private final MatchSessionState state;
    private final MatchBroadcaster broadcaster;

    /** 比赛在开赛前或休息期认输：直接以当前比分结束整场比赛。 */
    void finishByForfeit(MatchRecord match, String winnerUserId, String reason) {
        state.clearReady(match.id());
        state.clearMatchDisconnects(match.id());
        publisher.publishEvent(new MatchFinishedEvent(match.id()));
        broadcaster.broadcastMatch(match);
        broadcaster.send(match, Map.of(
                "type", "match_result",
                "matchId", match.id(),
                "winnerUserId", winnerUserId,
                "reason", reason,
                "player1Score", match.player1Score(),
                "player2Score", match.player2Score()));
    }

    /**
     * 结束当前小局。先条件更新比赛/小局状态，保证超时、认输、断线并发时只结算一次。
     */
    void finishGame(MatchRecord match, RoundRecord round, String gameWinner, String reason) {
        if (!"RUNNING".equals(match.status())) {
            return;
        }
        MatchRecord current = Optional.ofNullable(matches.selectById(match.id()))
                .map(MatchConverters::toRecord)
                .orElse(null);
        RoundRecord latest = Optional.ofNullable(matches.selectLatestRound(match.id()))
                .map(MatchConverters::toRecord)
                .orElse(null);
        if (current == null || latest == null || !latest.id().equals(round.id())
                || !"RUNNING".equals(latest.status())) {
            return;
        }
        TournamentRecord tournament = Optional.ofNullable(
                        tournaments.selectActiveById(current.tournamentId()))
                .map(TournamentConverters::toRecord)
                .orElse(null);
        boolean blind = tournament != null && GameIds.BLIND_XIANGQI.equals(tournament.gameId());
        // 赛事只认输赢：小局和棋时按种子顺位判高顺位获胜，避免无胜者的比赛阻塞赛程。
        String resolvedWinner = gameWinner;
        String resolvedReason = reason;
        if (resolvedWinner == null && tournament != null
                && TournamentService.KIND_TOURNAMENT.equals(tournament.kind())) {
            resolvedWinner = higherSeedWinner(current);
            resolvedReason = reason + " · 高顺位晋级";
        }
        // 积分赛统一 BO1，页面淘汰赛与阶梯决赛沿用创建时选择的赛制。
        int maxGames = BracketPlanner.STAGE_LEAGUE.equals(current.stage())
                ? 1
                : maxGames(tournament == null ? "BO1" : tournament.format());
        int winsNeeded = (maxGames + 1) / 2;

        int player1Score = current.player1Score();
        int player2Score = current.player2Score();
        if (resolvedWinner != null) {
            if (resolvedWinner.equals(current.player1UserId())) {
                player1Score += 1;
            } else {
                player2Score += 1;
            }
        }
        long now = System.currentTimeMillis();
        // 盲棋“将帅见面”记为和棋并直接进下一局；BO1 也重开直到分出胜负。
        boolean matchOver = blind
                ? gameWinner != null
                        && (player1Score >= winsNeeded || player2Score >= winsNeeded)
                : player1Score >= winsNeeded
                        || player2Score >= winsNeeded
                        || latest.gameNo() >= maxGames;

        if (matchOver) {
            String finalWinner;
            if (player1Score >= winsNeeded) {
                finalWinner = current.player1UserId();
            } else if (player2Score >= winsNeeded) {
                finalWinner = current.player2UserId();
            } else if (player1Score > player2Score) {
                finalWinner = current.player1UserId();
            } else if (player2Score > player1Score) {
                finalWinner = current.player2UserId();
            } else {
                // 极端和棋情况下按 1 号种子优势晋级，保证赛事可以收敛。
                finalWinner = current.player1UserId();
            }
            if (matches.finishIfRunning(
                    current.id(), finalWinner, player1Score, player2Score,
                    SqlTime.toDateTime(now)) == 0) {
                return;
            }
            matches.finishRoundIfRunning(latest.id(), resolvedWinner, SqlTime.toDateTime(now));
            closeRoundResources(latest.id());
            state.clearReady(current.id());
            state.clearMatchDisconnects(current.id());
            publisher.publishEvent(new MatchFinishedEvent(current.id()));
            broadcaster.broadcastMatch(current);
            broadcaster.send(current, Map.of(
                    "type", "match_result",
                    "matchId", current.id(),
                    "winnerUserId", finalWinner,
                    "reason", resolvedReason,
                    "player1Score", player1Score,
                    "player2Score", player2Score));
            return;
        }

        long restSeconds = ArenaGames.isArena(latest.gameId())
                ? arena.restSeconds() : properties.restSeconds();
        long restUntil = now + restSeconds * 1000L;
        if (matches.restAfterGame(latest.id(), resolvedWinner,
                SqlTime.toDateTime(restUntil), SqlTime.toDateTime(now)) == 0) {
            return;
        }
        closeRoundResources(latest.id());
        matches.updateScore(current.id(), player1Score, player2Score);
        state.clearReady(current.id());
        broadcaster.broadcastMatch(current);
        broadcaster.send(current, Map.of(
                "type", "match_rest",
                "matchId", current.id(),
                "restUntil", restUntil,
                "player1Score", player1Score,
                "player2Score", player2Score));
    }

    void closeRoundResources(String roundId) {
        arena.closeRound(roundId);
        exercise.closeRoom(roundId);
    }

    /** 和棋或平局时按种子顺位判定，种子数字越小顺位越高。 */
    private String higherSeedWinner(MatchRecord match) {
        int firstSeed = Optional.ofNullable(
                        tournaments.selectPlayer(match.tournamentId(), match.player1UserId()))
                .map(TournamentConverters::toRecord)
                .map(TournamentPlayerRecord::seed)
                .orElse(Integer.MAX_VALUE);
        int secondSeed = Optional.ofNullable(
                        tournaments.selectPlayer(match.tournamentId(), match.player2UserId()))
                .map(TournamentConverters::toRecord)
                .map(TournamentPlayerRecord::seed)
                .orElse(Integer.MAX_VALUE);
        return secondSeed < firstSeed ? match.player2UserId() : match.player1UserId();
    }

    private static int maxGames(String format) {
        return switch (format) {
            case "BO5" -> 5;
            case "BO3" -> 3;
            default -> 1;
        };
    }
}
