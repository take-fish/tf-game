package com.fishmatrix.game.service;

import com.fishmatrix.game.arena.ArenaGames;
import com.fishmatrix.game.arena.ArenaService;
import com.fishmatrix.game.arena.ExerciseEngine;
import com.fishmatrix.game.model.BlindLayout;
import com.fishmatrix.game.model.GameIds;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.RoundRecord;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.service.BlindXiangqiEngine.Piece;
import com.fishmatrix.game.store.BlindXiangqiRepository;
import com.fishmatrix.game.store.MatchRepository;
import com.fishmatrix.game.store.TournamentRepository;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 小局开局：首局/换先、剧本抽取，以及竞技场与巡检对抗引擎的初始化状态。
 */
@RequiredArgsConstructor
@Service
class MatchRoundService {

    private final MatchRepository matches;
    private final TournamentRepository tournaments;
    private final BlindXiangqiRepository blindPieces;
    private final ArenaService arena;
    private final ExerciseService exercise;
    private final GameNotificationService notifications;
    private final GameSessionRegistry registry;
    private final MatchSessionState state;
    private final MatchBroadcaster broadcaster;
    private final MatchViewAssembler assembler;

    /** 双方都准备后开第一局：随机先手。 */
    void startFirstGame(MatchRecord match) {
        long now = System.currentTimeMillis();
        if (matches.start(match.id(), now) == 0) {
            return;
        }
        boolean player1Black = ThreadLocalRandom.current().nextBoolean();
        String black = player1Black ? match.player1UserId() : match.player2UserId();
        String white = player1Black ? match.player2UserId() : match.player1UserId();
        insertRound(match, 1, black, white, now, firstGame(match));
        markMissingPlayers(match);
        broadcaster.broadcastMatch(match);
        notifyFinalStart(match);
    }

    /** 双方都点了“立即开始”，或 60 秒休息结束，进入下一小局。 */
    void startNextGame(String matchId) {
        MatchRecord match = matches.findById(matchId).orElse(null);
        if (match == null || !"RUNNING".equals(match.status())) {
            return;
        }
        RoundRecord previous = matches.latestRound(matchId).orElse(null);
        if (previous == null || !"REST".equals(previous.status())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (matches.finishRestRound(previous.id(), now) == 0) {
            return;
        }
        // 每局换先；拓扑攻防中先手同时也交换攻守身份。
        String black = previous.whiteUserId();
        String white = previous.blackUserId();
        insertRound(match, previous.gameNo() + 1, black, white, now, nextGame(match));
        state.clearReady(matchId);
        markMissingPlayers(match);
        broadcaster.broadcastMatch(match);
    }

    private void insertRound(MatchRecord match, int gameNo, String black, String white,
                             long now, String roundGameId) {
        String roundId = UUID.randomUUID().toString();
        String gameId = roundGameId == null ? TournamentService.GAME_ID : roundGameId;
        boolean arenaGame = ArenaGames.isArena(gameId) && arena.supports(gameId);
        boolean exerciseGame = exercise.supports(gameId);
        String attacker = null;
        String stateJson = null;
        Long endsAt = null;
        if (arenaGame) {
            List<String> players = List.of(match.player1UserId(), match.player2UserId());
            attacker = ArenaGames.TOPOLOGY.equals(gameId) ? black : null;
            ArenaService.StartState start = arena.startState(
                    gameId, players, attacker, now, roundId);
            stateJson = start.stateJson();
            endsAt = start.endsAt();
        } else if (exerciseGame) {
            // 巡检对抗按 TEAM 模式开局：双方各占一队，最终按队伍总分判定胜负。
            List<ExerciseEngine.PlayerSeed> seeds = List.of(
                    new ExerciseEngine.PlayerSeed(
                            match.player1UserId(), nickname(match.player1Nickname(),
                            match.player1UserId()), "A", false),
                    new ExerciseEngine.PlayerSeed(
                            match.player2UserId(), nickname(match.player2Nickname(),
                            match.player2UserId()), "B", false));
            ExerciseService.StartState start = exercise.startTournament(
                    match.id(), roundId, seeds, now);
            stateJson = start.stateJson();
            endsAt = start.endsAt();
        }
        RoundRecord round = new RoundRecord(
                roundId, match.id(), gameId, gameNo, "RUNNING",
                black, white, attacker, arenaGame || exerciseGame ? null : black,
                null, 0, now, null, "", stateJson, 0, now, endsAt, null);
        matches.insertRound(round);
        TournamentRecord tournament = assembler.tournamentOf(match);
        if (tournament != null && GameIds.BLIND_XIANGQI.equals(gameId)) {
            BlindLayout layout = BlindLayout.parse(tournament.blindLayout());
            List<Piece> pieces = BlindXiangqiEngine.generateLayout(layout).stream()
                    .map(placement -> new Piece(
                            UUID.randomUUID().toString(), placement.color(), placement.type(),
                            placement.x(), placement.y(), false))
                    .toList();
            blindPieces.insertPieces(roundId, pieces);
        }
    }

    /**
     * 首局剧本：页面淘汰赛可能被高顺位选手改选；系统随机赛事在没有改选时由服务端抽签。
     */
    private String firstGame(MatchRecord match) {
        if (match.firstGameId() != null) {
            return match.firstGameId();
        }
        TournamentRecord tournament = assembler.tournamentOf(match);
        String configured = tournament == null ? TournamentService.GAME_ID : tournament.gameId();
        return ArenaGames.isRandom(configured) ? ArenaGames.randomGame() : configured;
    }

    /** 后续小局：系统随机赛事每局重新抽签，固定剧本赛事继续使用同一剧本。 */
    private String nextGame(MatchRecord match) {
        TournamentRecord tournament = assembler.tournamentOf(match);
        String configured = tournament == null ? TournamentService.GAME_ID : tournament.gameId();
        return ArenaGames.isRandom(configured) ? ArenaGames.randomGame() : configured;
    }

    private void markMissingPlayers(MatchRecord match) {
        for (String userId : MatchBroadcaster.participantIds(match)) {
            if (!registry.byUser(userId).isEmpty()) {
                continue;
            }
            state.markDisconnect(match.id(), userId);
        }
    }

    private void notifyFinalStart(MatchRecord match) {
        tournaments.findById(match.tournamentId()).ifPresent(tournament -> {
            // 约战没有全站播报，也不产生冠军通知。
            if (TournamentService.KIND_DUEL.equals(tournament.kind())) {
                return;
            }
            boolean finalStage = BracketPlanner.STAGE_KNOCKOUT.equals(match.stage())
                    || BracketPlanner.STAGE_LADDER.equals(match.stage())
                    || BracketPlanner.STAGE_PAGE_PLAYOFF.equals(match.stage());
            if (finalStage && match.roundNo() == tournament.totalRounds()) {
                notifications.publishFinalStart(tournament, match);
            }
        });
    }

    private static String nickname(String nickname, String userId) {
        return nickname == null || nickname.isBlank() ? userId : nickname;
    }
}
