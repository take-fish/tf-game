package com.fishmatrix.game.service;

import com.fishmatrix.game.arena.ArenaGames;
import com.fishmatrix.game.arena.ArenaRoundFinishedEvent;
import com.fishmatrix.game.arena.ArenaService;
import com.fishmatrix.game.arena.ExerciseEngine;
import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.model.BlindColor;
import com.fishmatrix.game.model.BlindLayout;
import com.fishmatrix.game.model.BlindPieceType;
import com.fishmatrix.game.model.BlindPieceView;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.GameIds;
import com.fishmatrix.game.model.MatchFinishedEvent;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.model.MoveView;
import com.fishmatrix.game.model.RoundRecord;
import com.fishmatrix.game.model.RoundView;
import com.fishmatrix.game.model.TournamentPlayerRecord;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.service.BlindXiangqiEngine.Piece;
import com.fishmatrix.game.store.BlindXiangqiRepository;
import com.fishmatrix.game.store.MatchRepository;
import com.fishmatrix.game.store.TournamentRepository;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一场 BO 比赛内的对局（五子棋 / 盲棋）：
 * - 双方准备后开局，首局随机先手，之后每局换先。
 * - 每步 30 秒超时判负；断线 60 秒判负；支持认输。
 * - 小局结束休息 60 秒，双方可确认立即开始。
 */
@Service
public class MatchService {

    private static final Logger log = LoggerFactory.getLogger(MatchService.class);

    private final MatchRepository matches;
    private final TournamentRepository tournaments;
    private final BlindXiangqiRepository blindPieces;
    private final GameSessionRegistry registry;
    private final GameNotificationService notifications;
    private final ApplicationEventPublisher publisher;
    private final GameProperties properties;
    private final ArenaService arena;
    private final ExerciseService exercise;

    private final Map<String, Set<String>> readyUsers = new ConcurrentHashMap<>();
    private final Map<String, Long> disconnectAt = new ConcurrentHashMap<>();

    public MatchService(
            MatchRepository matches,
            TournamentRepository tournaments,
            BlindXiangqiRepository blindPieces,
            GameSessionRegistry registry,
            GameNotificationService notifications,
            ApplicationEventPublisher publisher,
            GameProperties properties,
            ArenaService arena,
            ExerciseService exercise) {
        this.matches = matches;
        this.tournaments = tournaments;
        this.blindPieces = blindPieces;
        this.registry = registry;
        this.notifications = notifications;
        this.publisher = publisher;
        this.properties = properties;
        this.arena = arena;
        this.exercise = exercise;
    }

    public MatchView detail(String matchId) {
        MatchRecord match = requireMatch(matchId);
        TournamentRecord tournament = tournamentOf(match);
        RoundRecord round = matches.latestRound(matchId).orElse(null);
        return toView(match, tournament, round == null ? null : toRoundView(round, tournament));
    }

    /** 约战详情只对双方开放，普通赛事保持公开观战。 */
    public MatchView detailForUser(GameIdentity user, String matchId) {
        MatchRecord match = requireMatch(matchId);
        TournamentRecord tournament = tournamentOf(match);
        String kind = tournament == null ? TournamentService.KIND_TOURNAMENT : tournament.kind();
        if (TournamentService.KIND_DUEL.equals(kind) && !isParticipant(match, user.userId())) {
            throw GameException.forbidden("约战仅双方可见");
        }
        RoundRecord round = matches.latestRound(matchId).orElse(null);
        return toView(match, tournament, round == null ? null : toRoundView(round, tournament));
    }

    public List<MatchView> activeForUser(String userId) {
        return matches.listReadyOrRunningByUser(userId).stream()
                .map(match -> toView(match, tournamentOf(match), null))
                .toList();
    }

    @Transactional
    public MatchView ready(GameIdentity user, String matchId) {
        MatchRecord match = requireParticipant(user, matchId);
        if ("READY".equals(match.status())) {
            Set<String> ready = readyUsers.computeIfAbsent(
                    matchId, key -> ConcurrentHashMap.newKeySet());
            ready.add(user.userId());
            if (ready.contains(match.player1UserId()) && ready.contains(match.player2UserId())) {
                readyUsers.remove(matchId);
                startFirstGame(match);
            } else {
                registry.broadcastMatch(matchId, participantIds(match), Map.of(
                        "type", "match_ready",
                        "matchId", matchId,
                        "readyUserIds", List.copyOf(ready)));
            }
            return detail(matchId);
        }
        if ("RUNNING".equals(match.status())) {
            RoundRecord round = matches.latestRound(matchId).orElse(null);
            if (round == null || !"REST".equals(round.status())) {
                throw GameException.conflict("GAME_MATCH_STARTED", "比赛已经开始");
            }
            Set<String> ready = readyUsers.computeIfAbsent(
                    matchId, key -> ConcurrentHashMap.newKeySet());
            ready.add(user.userId());
            matches.updateReadyUserIds(round.id(), String.join(",", ready));
            if (ready.contains(match.player1UserId()) && ready.contains(match.player2UserId())) {
                readyUsers.remove(matchId);
                startNextGame(matchId);
                return detail(matchId);
            }
            broadcastMatch(match);
            return detail(matchId);
        }
        throw GameException.conflict("GAME_MATCH_CLOSED", "比赛已结束");
    }

    private void startFirstGame(MatchRecord match) {
        long now = System.currentTimeMillis();
        if (matches.start(match.id(), now) == 0) {
            return;
        }
        boolean player1Black = ThreadLocalRandom.current().nextBoolean();
        String black = player1Black ? match.player1UserId() : match.player2UserId();
        String white = player1Black ? match.player2UserId() : match.player1UserId();
        insertRound(match, 1, black, white, now, firstGame(match));
        markMissingPlayers(match);
        broadcastMatch(match);
        notifyFinalStart(match);
    }

    @Transactional
    public MatchView placeStone(GameIdentity user, String matchId, Integer x, Integer y) {
        if (x == null || y == null) {
            throw GameException.badRequest("GAME_MOVE_INVALID", "落子坐标不能为空");
        }
        if (!GomokuEngine.inBounds(x, y)) {
            throw GameException.badRequest("GAME_MOVE_INVALID", "落子超出棋盘");
        }
        MatchRecord match = requireParticipant(user, matchId);
        requireGomoku(match);
        if (!"RUNNING".equals(match.status())) {
            throw GameException.conflict("GAME_MATCH_NOT_RUNNING", "比赛尚未开始");
        }
        RoundRecord round = matches.latestRound(matchId)
                .orElseThrow(() -> GameException.conflict("GAME_ROUND_MISSING", "对局尚未开始"));
        if (!"RUNNING".equals(round.status())) {
            throw GameException.conflict("GAME_ROUND_NOT_RUNNING", "当前小局不在对弈中");
        }
        if (ArenaGames.isArena(round.gameId())) {
            throw GameException.conflict("GAME_ARENA_NOT_STONES", "该小局为实时演练，请使用演练操作");
        }
        if (!user.userId().equals(round.turnUserId())) {
            throw GameException.forbidden("还没轮到你落子");
        }

        List<MoveView> moves = matches.listMoves(round.id());
        int[][] board = GomokuEngine.board(moves, round.blackUserId());
        if (board[x][y] != GomokuEngine.EMPTY) {
            throw GameException.conflict("GAME_MOVE_OCCUPIED", "该位置已有棋子");
        }
        String opponent = user.userId().equals(match.player1UserId())
                ? match.player2UserId() : match.player1UserId();
        long now = System.currentTimeMillis();
        int nextMoveNo = round.moveCount() + 1;
        int updated = matches.updateTurnAndCount(
                round.id(), round.moveCount(), nextMoveNo, opponent, now);
        if (updated == 0) {
            throw GameException.conflict("GAME_MOVE_CONFLICT", "落子冲突，请刷新棋盘");
        }
        matches.insertMove(round.id(), nextMoveNo, user.userId(), x, y, now);

        board[x][y] = user.userId().equals(round.blackUserId())
                ? GomokuEngine.BLACK : GomokuEngine.WHITE;
        boolean win = GomokuEngine.isWinningMove(board, x, y);
        boolean full = !win && GomokuEngine.isFull(board);

        registry.broadcastMatch(matchId, participantIds(match), Map.of(
                "type", "stone",
                "matchId", matchId,
                "roundId", round.id(),
                "moveNo", nextMoveNo,
                "userId", user.userId(),
                "x", x,
                "y", y,
                "turnUserId", opponent,
                "moveCount", nextMoveNo,
                "winning", win));

        if (win || full) {
            finishGame(match, round, win ? user.userId() : null, win ? "五连获胜" : "和棋");
        } else {
            broadcastMatch(match);
        }
        return detail(matchId);
    }

    /** 盲棋翻棋：花一步揭开一枚反面棋，翻完即换对方行动。 */
    @Transactional
    public MatchView revealPiece(GameIdentity user, String matchId, Integer x, Integer y) {
        if (x == null || y == null || !BlindXiangqiEngine.inBounds(x, y)) {
            throw GameException.badRequest("GAME_MOVE_INVALID", "翻棋坐标不合法");
        }
        BlindRound state = requireBlindRound(user, matchId);
        Piece target = BlindXiangqiEngine.pieceAt(state.pieces(), x, y);
        if (target == null) {
            throw GameException.conflict("GAME_MOVE_EMPTY", "该位置没有棋子");
        }
        if (target.revealed()) {
            throw GameException.conflict("GAME_PIECE_REVEALED", "该棋子已经翻开");
        }
        return applyBlindAction(state, user, "REVEAL", target, null, null, x, y, null);
    }

    /** 盲棋走子：已翻开的己方棋按中国象棋规则移动或吃子。 */
    @Transactional
    public MatchView movePiece(GameIdentity user, String matchId,
                               Integer fromX, Integer fromY, Integer toX, Integer toY) {
        if (fromX == null || fromY == null || toX == null || toY == null
                || !BlindXiangqiEngine.inBounds(fromX, fromY)
                || !BlindXiangqiEngine.inBounds(toX, toY)) {
            throw GameException.badRequest("GAME_MOVE_INVALID", "走子坐标不合法");
        }
        BlindRound state = requireBlindRound(user, matchId);
        Piece moving = BlindXiangqiEngine.pieceAt(state.pieces(), fromX, fromY);
        if (moving == null) {
            throw GameException.conflict("GAME_MOVE_EMPTY", "起点没有棋子");
        }
        BlindColor color = blindColor(state.round(), user.userId());
        if (!BlindXiangqiEngine.canMove(
                state.pieces(), color, fromX, fromY, toX, toY)) {
            throw GameException.badRequest("GAME_MOVE_INVALID", "该走法不合法");
        }
        Piece captured = BlindXiangqiEngine.pieceAt(state.pieces(), toX, toY);
        return applyBlindAction(
                state, user, "MOVE", moving, fromX, fromY, toX, toY, captured);
    }

    private MatchView applyBlindAction(BlindRound state, GameIdentity user, String action,
                                       Piece piece, Integer fromX, Integer fromY,
                                       int toX, int toY, Piece captured) {
        MatchRecord match = state.match();
        RoundRecord round = state.round();
        long now = System.currentTimeMillis();
        int nextMoveNo = round.moveCount() + 1;
        String opponent = opponentOf(match, user.userId());
        if (matches.updateTurnAndCount(
                round.id(), round.moveCount(), nextMoveNo, opponent, now) == 0) {
            throw GameException.conflict("GAME_MOVE_CONFLICT", "操作冲突，请刷新棋盘");
        }

        boolean reveal = "REVEAL".equals(action);
        if (reveal) {
            if (blindPieces.reveal(round.id(), piece.id()) == 0) {
                throw GameException.conflict("GAME_MOVE_CONFLICT", "翻棋冲突，请刷新棋盘");
            }
        } else {
            if (blindPieces.move(round.id(), piece.id(), toX, toY) == 0) {
                throw GameException.conflict("GAME_MOVE_CONFLICT", "走子冲突，请刷新棋盘");
            }
            if (captured != null && blindPieces.capture(round.id(), captured.id()) == 0) {
                throw GameException.conflict("GAME_MOVE_CONFLICT", "吃子冲突，请刷新棋盘");
            }
        }

        matches.insertMove(round.id(), nextMoveNo, user.userId(), toX, toY, action,
                fromX, fromY, piece.type().name(), piece.color().name(),
                captured == null ? null : captured.type().name(),
                captured == null ? null : captured.color().name(), now);

        List<Piece> nextPieces = new ArrayList<>(state.pieces().size());
        for (Piece current : state.pieces()) {
            if (captured != null && current.id().equals(captured.id())) {
                continue;
            }
            if (current.id().equals(piece.id())) {
                nextPieces.add(reveal ? current.reveal() : current.moveTo(toX, toY));
            } else {
                nextPieces.add(current);
            }
        }

        String winner = null;
        if (captured != null && captured.type() == BlindPieceType.GENERAL) {
            // 将/帅先死的一方输，包括盲吃误伤己方将帅。
            winner = userIdOfColor(round, captured.color().opponent());
        }
        boolean draw = winner == null && BlindXiangqiEngine.generalsFacing(nextPieces);

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "blind_action");
        frame.put("matchId", match.id());
        frame.put("roundId", round.id());
        frame.put("moveNo", nextMoveNo);
        frame.put("userId", user.userId());
        frame.put("action", action);
        frame.put("fromX", fromX);
        frame.put("fromY", fromY);
        frame.put("toX", toX);
        frame.put("toY", toY);
        frame.put("pieceType", piece.type().name());
        frame.put("pieceColor", piece.color().name());
        frame.put("capturedPieceType", captured == null ? null : captured.type().name());
        frame.put("capturedPieceColor", captured == null ? null : captured.color().name());
        frame.put("turnUserId", opponent);
        frame.put("moveCount", nextMoveNo);
        frame.put("result", winner != null ? "WIN" : draw ? "DRAW" : null);
        registry.broadcastMatch(match.id(), participantIds(match), frame);

        if (winner != null) {
            finishGame(match, round, winner, "将帅被吃");
        } else if (draw) {
            finishGame(match, round, null, "将帅见面和棋");
        } else {
            broadcastMatch(match);
        }
        return detail(match.id());
    }

    @Transactional
    public MatchView resign(GameIdentity user, String matchId) {
        MatchRecord match = requireParticipant(user, matchId);
        if ("FINISHED".equals(match.status()) || "BYE".equals(match.status())) {
            throw GameException.conflict("GAME_MATCH_CLOSED", "比赛已结束");
        }
        String opponent = user.userId().equals(match.player1UserId())
                ? match.player2UserId() : match.player1UserId();
        long now = System.currentTimeMillis();
        RoundRecord round = matches.latestRound(matchId).orElse(null);

        if ("READY".equals(match.status()) || round == null) {
            if (matches.finishActive(match.id(), opponent,
                    match.player1Score(), match.player2Score(), now) == 0) {
                return detail(matchId);
            }
            finishByForfeit(match, opponent, "认输");
            return detail(matchId);
        }
        if ("REST".equals(round.status())) {
            if (matches.finishActive(match.id(), opponent,
                    match.player1Score(), match.player2Score(), now) == 0) {
                return detail(matchId);
            }
            matches.finishRestRound(round.id(), now);
            finishByForfeit(match, opponent, "认输");
            return detail(matchId);
        }
        if ("RUNNING".equals(round.status())) {
            finishGame(match, round, opponent, "认输");
            return detail(matchId);
        }
        throw GameException.conflict("GAME_MATCH_CLOSED", "比赛已结束");
    }

    private void finishByForfeit(MatchRecord match, String winnerUserId, String reason) {
        readyUsers.remove(match.id());
        clearDisconnect(match.id());
        publisher.publishEvent(new MatchFinishedEvent(match.id()));
        broadcastMatch(match);
        registry.broadcastMatch(match.id(), participantIds(match), Map.of(
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
    private void finishGame(MatchRecord match, RoundRecord round, String gameWinner, String reason) {
        if (!"RUNNING".equals(match.status())) {
            return;
        }
        MatchRecord current = matches.findById(match.id()).orElse(null);
        RoundRecord latest = matches.latestRound(match.id()).orElse(null);
        if (current == null || latest == null || !latest.id().equals(round.id())
                || !"RUNNING".equals(latest.status())) {
            return;
        }
        TournamentRecord tournament = tournaments.findById(current.tournamentId()).orElse(null);
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
                    current.id(), finalWinner, player1Score, player2Score, now) == 0) {
                return;
            }
            matches.finishRoundIfRunning(latest.id(), resolvedWinner, now);
            arena.closeRound(latest.id());
            exercise.closeRoom(latest.id());
            readyUsers.remove(current.id());
            clearDisconnect(current.id());
            publisher.publishEvent(new MatchFinishedEvent(current.id()));
            broadcastMatch(current);
            registry.broadcastMatch(current.id(), participantIds(current), Map.of(
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
        if (matches.restAfterGame(latest.id(), resolvedWinner, restUntil, now) == 0) {
            return;
        }
        arena.closeRound(latest.id());
        exercise.closeRoom(latest.id());
        matches.updateScore(current.id(), player1Score, player2Score);
        readyUsers.remove(current.id());
        broadcastMatch(current);
        registry.broadcastMatch(current.id(), participantIds(current), Map.of(
                "type", "match_rest",
                "matchId", current.id(),
                "restUntil", restUntil,
                "player1Score", player1Score,
                "player2Score", player2Score));
    }

    /** 双方都点了“立即开始”，或 60 秒休息结束，进入下一小局。 */
    @Transactional
    public void startNextGame(String matchId) {
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
        readyUsers.remove(matchId);
        markMissingPlayers(match);
        broadcastMatch(match);
    }

    /** 每秒检查：休息到期自动开局、每步超时、断线超时。 */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void tick() {
        long now = System.currentTimeMillis();
        for (RoundRecord round : matches.listDueRest(now)) {
            try {
                startNextGame(round.matchId());
            } catch (Exception exception) {
                log.warn("自动开始下一局失败 match={}", round.matchId(), exception);
            }
        }
        long moveDeadline = properties.moveTimeoutSeconds() * 1000L;
        for (RoundRecord round : matches.listRunningRounds()) {
            if (now - round.lastMoveAt() >= moveDeadline) {
                try {
                    timeout(round);
                } catch (Exception exception) {
                    log.warn("落子超时判定失败 round={}", round.id(), exception);
                }
            }
        }
        checkDisconnects(now);
    }

    private void timeout(RoundRecord round) {
        MatchRecord match = matches.findById(round.matchId()).orElse(null);
        if (match == null || !"RUNNING".equals(match.status())
                || round.turnUserId() == null) {
            return;
        }
        String winner = round.turnUserId().equals(match.player1UserId())
                ? match.player2UserId() : match.player1UserId();
        finishGame(match, round, winner, "超时判负");
    }

    public void onConnect(String userId) {
        String suffix = "|" + userId;
        disconnectAt.keySet().removeIf(key -> key.endsWith(suffix));
    }

    public void onDisconnect(String userId) {
        long now = System.currentTimeMillis();
        for (MatchRecord match : matches.listReadyOrRunningByUser(userId)) {
            if ("RUNNING".equals(match.status())) {
                disconnectAt.put(disconnectKey(match.id(), userId), now);
            }
        }
    }

    private void markMissingPlayers(MatchRecord match) {
        long now = System.currentTimeMillis();
        for (String userId : participantIds(match)) {
            if (registry.byUser(userId).isEmpty()) {
                disconnectAt.put(disconnectKey(match.id(), userId), now);
            }
        }
    }

    private void checkDisconnects(long now) {
        long deadline = properties.disconnectTimeoutSeconds() * 1000L;
        List<String> keys = new ArrayList<>(disconnectAt.keySet());
        for (String key : keys) {
            Long since = disconnectAt.get(key);
            if (since == null || now - since < deadline) {
                continue;
            }
            int separator = key.indexOf('|');
            if (separator <= 0) {
                disconnectAt.remove(key);
                continue;
            }
            String matchId = key.substring(0, separator);
            String userId = key.substring(separator + 1);
            MatchRecord match = matches.findById(matchId).orElse(null);
            if (match == null || !"RUNNING".equals(match.status())) {
                disconnectAt.remove(key);
                continue;
            }
            if (!registry.byUser(userId).isEmpty()) {
                disconnectAt.remove(key);
                continue;
            }
            RoundRecord round = matches.latestRound(matchId).orElse(null);
            if (round == null) {
                disconnectAt.remove(key);
                continue;
            }
            if (!"RUNNING".equals(round.status())) {
                // 休息中不断言，等下一局开始后继续计时。
                continue;
            }
            String winner = userId.equals(match.player1UserId())
                    ? match.player2UserId() : match.player1UserId();
            finishGame(match, round, winner, "断线判负");
        }
    }

    /**
     * 首局剧本：页面淘汰赛可能被高顺位选手改选；系统随机赛事在没有改选时由服务端抽签。
     */
    private String firstGame(MatchRecord match) {
        if (match.firstGameId() != null) {
            return match.firstGameId();
        }
        TournamentRecord tournament = tournaments.findById(match.tournamentId()).orElse(null);
        String configured = tournament == null ? TournamentService.GAME_ID : tournament.gameId();
        return ArenaGames.isRandom(configured) ? ArenaGames.randomGame() : configured;
    }

    /** 后续小局：系统随机赛事每局重新抽签，固定剧本赛事继续使用同一剧本。 */
    private String nextGame(MatchRecord match) {
        TournamentRecord tournament = tournaments.findById(match.tournamentId()).orElse(null);
        String configured = tournament == null ? TournamentService.GAME_ID : tournament.gameId();
        return ArenaGames.isRandom(configured) ? ArenaGames.randomGame() : configured;
    }

    /** 和棋或平局时按种子顺位判定，种子数字越小顺位越高。 */
    private String higherSeedWinner(MatchRecord match) {
        int firstSeed = tournaments.findPlayer(match.tournamentId(), match.player1UserId())
                .map(TournamentPlayerRecord::seed)
                .orElse(Integer.MAX_VALUE);
        int secondSeed = tournaments.findPlayer(match.tournamentId(), match.player2UserId())
                .map(TournamentPlayerRecord::seed)
                .orElse(Integer.MAX_VALUE);
        return secondSeed < firstSeed ? match.player2UserId() : match.player1UserId();
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
        TournamentRecord tournament = tournamentOf(match);
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

    private MatchRecord requireMatch(String matchId) {
        return matches.findById(matchId)
                .orElseThrow(() -> GameException.notFound("比赛不存在"));
    }

    private MatchRecord requireParticipant(GameIdentity user, String matchId) {
        MatchRecord match = requireMatch(matchId);
        if (!isParticipant(match, user.userId())) {
            throw GameException.forbidden("只有参赛选手可以操作比赛");
        }
        return match;
    }

    private static boolean isParticipant(MatchRecord match, String userId) {
        return userId != null
                && (userId.equals(match.player1UserId()) || userId.equals(match.player2UserId()));
    }

    private RoundView toRoundView(RoundRecord round, TournamentRecord tournament) {
        List<String> ready = round.readyUserIds() == null || round.readyUserIds().isBlank()
                ? List.of()
                : Arrays.stream(round.readyUserIds().split(","))
                        .map(String::strip)
                        .filter(value -> !value.isEmpty())
                        .toList();
        List<BlindPieceView> blindBoard = List.of();
        if (tournament != null && GameIds.BLIND_XIANGQI.equals(tournament.gameId())) {
            blindBoard = blindPieces.listAlivePieces(round.id()).stream()
                    .map(piece -> piece.revealed()
                            ? BlindPieceView.revealed(
                                    piece.x(), piece.y(), piece.color(), piece.type())
                            : BlindPieceView.hidden(piece.x(), piece.y()))
                    .toList();
        }
        return new RoundView(
                round.id(), round.gameId(), round.gameNo(), round.status(),
                round.blackUserId(), round.whiteUserId(), round.attackerUserId(),
                round.turnUserId(), round.winnerUserId(),
                round.moveCount(), round.lastMoveAt(), round.restUntil(), ready,
                round.startedAt(), round.endsAt(), round.finishedAt(),
                round.stateVersion(), arena.stateView(round),
                ArenaGames.isArena(round.gameId())
                        ? List.of() : matches.listMoves(round.id()),
                blindBoard, round.blackUserId(), round.whiteUserId());
    }

    private MatchView toView(MatchRecord match, TournamentRecord tournament, RoundView round) {
        String kind = tournament == null ? TournamentService.KIND_TOURNAMENT : tournament.kind();
        String gameId = round != null && round.gameId() != null
                ? round.gameId()
                : (tournament == null ? GameIds.HEATMAP_GOMOKU : tournament.gameId());
        String blindLayout = tournament == null ? null : tournament.blindLayout();
        return new MatchView(
                match.id(), match.tournamentId(), gameId, kind, match.roundNo(), match.slotNo(),
                match.stage(), match.status(), match.player1UserId(), match.player1Nickname(),
                match.player2UserId(), match.player2Nickname(), match.player1Score(),
                match.player2Score(), match.winnerUserId(), match.nextMatchId(),
                match.firstGameId(), match.gameChooserUserId(),
                match.createdAt(), match.startedAt(), match.finishedAt(), round, blindLayout);
    }

    private TournamentRecord tournamentOf(MatchRecord match) {
        return tournaments.findById(match.tournamentId()).orElse(null);
    }

    private void requireGomoku(MatchRecord match) {
        TournamentRecord tournament = tournamentOf(match);
        if (tournament != null && GameIds.BLIND_XIANGQI.equals(tournament.gameId())) {
            throw GameException.badRequest("GAME_ACTION_INVALID", "盲棋请使用翻棋或走子操作");
        }
    }

    private BlindRound requireBlindRound(GameIdentity user, String matchId) {
        MatchRecord match = requireParticipant(user, matchId);
        TournamentRecord tournament = tournamentOf(match);
        if (tournament == null || !GameIds.BLIND_XIANGQI.equals(tournament.gameId())) {
            throw GameException.badRequest("GAME_ACTION_INVALID", "当前比赛不是盲棋");
        }
        if (!"RUNNING".equals(match.status())) {
            throw GameException.conflict("GAME_MATCH_NOT_RUNNING", "比赛尚未开始");
        }
        RoundRecord round = matches.latestRound(matchId)
                .orElseThrow(() -> GameException.conflict("GAME_ROUND_MISSING", "对局尚未开始"));
        if (!"RUNNING".equals(round.status())) {
            throw GameException.conflict("GAME_ROUND_NOT_RUNNING", "当前小局不在对弈中");
        }
        if (!user.userId().equals(round.turnUserId())) {
            throw GameException.forbidden("还没轮到你操作");
        }
        return new BlindRound(match, round, blindPieces.listAlivePieces(round.id()));
    }

    /** 盲棋沿用 black_user_id 表示红方（先手），white_user_id 表示黑方。 */
    private static BlindColor blindColor(RoundRecord round, String userId) {
        return userId.equals(round.blackUserId()) ? BlindColor.RED : BlindColor.BLACK;
    }

    private static String userIdOfColor(RoundRecord round, BlindColor color) {
        return color == BlindColor.RED ? round.blackUserId() : round.whiteUserId();
    }

    private static String opponentOf(MatchRecord match, String userId) {
        return userId.equals(match.player1UserId())
                ? match.player2UserId() : match.player1UserId();
    }

    private record BlindRound(MatchRecord match, RoundRecord round, List<Piece> pieces) {
    }

    private String gameId(MatchRecord match, RoundRecord round) {
        if (round != null && round.gameId() != null) {
            return round.gameId();
        }
        if (match.firstGameId() != null) {
            return match.firstGameId();
        }
        return tournaments.findById(match.tournamentId())
                .map(TournamentRecord::gameId)
                .orElse(TournamentService.GAME_ID);
    }

    /** 竞技场引擎结算后统一走原有 BO 计分、休息与晋级流程。 */
    @EventListener
    @Transactional
    public void onArenaRoundFinished(ArenaRoundFinishedEvent event) {
        MatchRecord match = matches.findById(event.matchId()).orElse(null);
        if (match == null || !"RUNNING".equals(match.status())) {
            return;
        }
        RoundRecord round = matches.findRound(event.roundId()).orElse(null);
        if (round == null || !"RUNNING".equals(round.status())) {
            return;
        }
        finishGame(match, round, event.winnerUserId(), event.reason());
        arena.closeRound(event.roundId());
        exercise.closeRoom(event.roundId());
    }

    private void broadcastMatch(MatchRecord match) {
        MatchView view = detail(match.id());
        registry.broadcastMatch(match.id(), participantIds(match), Map.of(
                "type", "match_update",
                "match", view));
    }

    private static Collection<String> participantIds(MatchRecord match) {
        Set<String> ids = new LinkedHashSet<>();
        if (match.player1UserId() != null) {
            ids.add(match.player1UserId());
        }
        if (match.player2UserId() != null) {
            ids.add(match.player2UserId());
        }
        return ids;
    }

    private static String disconnectKey(String matchId, String userId) {
        return matchId + "|" + userId;
    }

    private static String nickname(String nickname, String userId) {
        return nickname == null || nickname.isBlank() ? userId : nickname;
    }

    private void clearDisconnect(String matchId) {
        String prefix = matchId + "|";
        disconnectAt.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static int maxGames(String format) {
        return switch (format) {
            case "BO5" -> 5;
            case "BO3" -> 3;
            default -> 1;
        };
    }
}
