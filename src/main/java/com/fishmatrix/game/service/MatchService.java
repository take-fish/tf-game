package com.fishmatrix.game.service;

import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchFinishedEvent;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.model.MoveView;
import com.fishmatrix.game.model.RoundRecord;
import com.fishmatrix.game.model.RoundView;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.store.MatchRepository;
import com.fishmatrix.game.store.TournamentRepository;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一场 BO 比赛内的五子棋对局：
 * - 双方准备后开局，首局随机黑，之后每局换先。
 * - 每步 30 秒超时判负；断线 60 秒判负；支持认输。
 * - 小局结束休息 60 秒，双方可确认立即开始。
 */
@Service
public class MatchService {

    private static final Logger log = LoggerFactory.getLogger(MatchService.class);

    private final MatchRepository matches;
    private final TournamentRepository tournaments;
    private final GameSessionRegistry registry;
    private final GameNotificationService notifications;
    private final ApplicationEventPublisher publisher;
    private final GameProperties properties;

    private final Map<String, Set<String>> readyUsers = new ConcurrentHashMap<>();
    private final Map<String, Long> disconnectAt = new ConcurrentHashMap<>();

    public MatchService(
            MatchRepository matches,
            TournamentRepository tournaments,
            GameSessionRegistry registry,
            GameNotificationService notifications,
            ApplicationEventPublisher publisher,
            GameProperties properties) {
        this.matches = matches;
        this.tournaments = tournaments;
        this.registry = registry;
        this.notifications = notifications;
        this.publisher = publisher;
        this.properties = properties;
    }

    public MatchView detail(String matchId) {
        MatchRecord match = requireMatch(matchId);
        RoundRecord round = matches.latestRound(matchId).orElse(null);
        return toView(match, tournamentKind(match), round == null ? null : toRoundView(round));
    }

    /** 约战详情只对双方开放，普通赛事保持公开观战。 */
    public MatchView detailForUser(GameIdentity user, String matchId) {
        MatchRecord match = requireMatch(matchId);
        String kind = tournamentKind(match);
        if (TournamentService.KIND_DUEL.equals(kind) && !isParticipant(match, user.userId())) {
            throw GameException.forbidden("约战仅双方可见");
        }
        RoundRecord round = matches.latestRound(matchId).orElse(null);
        return toView(match, kind, round == null ? null : toRoundView(round));
    }

    public List<MatchView> activeForUser(String userId) {
        return matches.listReadyOrRunningByUser(userId).stream()
                .map(match -> toView(match, tournamentKind(match), null))
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
        insertRound(match.id(), 1, black, white, now);
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
        if (!"RUNNING".equals(match.status())) {
            throw GameException.conflict("GAME_MATCH_NOT_RUNNING", "比赛尚未开始");
        }
        RoundRecord round = matches.latestRound(matchId)
                .orElseThrow(() -> GameException.conflict("GAME_ROUND_MISSING", "对局尚未开始"));
        if (!"RUNNING".equals(round.status())) {
            throw GameException.conflict("GAME_ROUND_NOT_RUNNING", "当前小局不在对弈中");
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
        // 积分赛统一 BO1，阶梯决赛沿用创建时选择的赛制。
        int maxGames = BracketPlanner.STAGE_LEAGUE.equals(current.stage())
                ? 1
                : maxGames(tournament == null ? "BO1" : tournament.format());
        int winsNeeded = (maxGames + 1) / 2;

        int player1Score = current.player1Score();
        int player2Score = current.player2Score();
        if (gameWinner != null) {
            if (gameWinner.equals(current.player1UserId())) {
                player1Score += 1;
            } else {
                player2Score += 1;
            }
        }
        long now = System.currentTimeMillis();
        boolean matchOver = player1Score >= winsNeeded
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
            matches.finishRoundIfRunning(latest.id(), gameWinner, now);
            readyUsers.remove(current.id());
            clearDisconnect(current.id());
            publisher.publishEvent(new MatchFinishedEvent(current.id()));
            broadcastMatch(current);
            registry.broadcastMatch(current.id(), participantIds(current), Map.of(
                    "type", "match_result",
                    "matchId", current.id(),
                    "winnerUserId", finalWinner,
                    "reason", reason,
                    "player1Score", player1Score,
                    "player2Score", player2Score));
            return;
        }

        long restUntil = now + properties.restSeconds() * 1000L;
        if (matches.restAfterGame(latest.id(), gameWinner, restUntil, now) == 0) {
            return;
        }
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
        // 每局换先。
        String black = previous.whiteUserId();
        String white = previous.blackUserId();
        insertRound(matchId, previous.gameNo() + 1, black, white, now);
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

    private void insertRound(String matchId, int gameNo, String black, String white, long now) {
        matches.insertRound(new RoundRecord(
                UUID.randomUUID().toString(), matchId, gameNo, "RUNNING",
                black, white, black, null, 0, now, null, "", now, null));
    }

    private void notifyFinalStart(MatchRecord match) {
        tournaments.findById(match.tournamentId()).ifPresent(tournament -> {
            // 约战没有全站播报，也不产生冠军通知。
            if (TournamentService.KIND_DUEL.equals(tournament.kind())) {
                return;
            }
            boolean finalStage = BracketPlanner.STAGE_KNOCKOUT.equals(match.stage())
                    || BracketPlanner.STAGE_LADDER.equals(match.stage());
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

    private RoundView toRoundView(RoundRecord round) {
        List<String> ready = round.readyUserIds() == null || round.readyUserIds().isBlank()
                ? List.of()
                : Arrays.stream(round.readyUserIds().split(","))
                        .map(String::strip)
                        .filter(value -> !value.isEmpty())
                        .toList();
        return new RoundView(
                round.id(), round.gameNo(), round.status(), round.blackUserId(),
                round.whiteUserId(), round.turnUserId(), round.winnerUserId(),
                round.moveCount(), round.lastMoveAt(), round.restUntil(), ready,
                round.startedAt(), round.finishedAt(), matches.listMoves(round.id()));
    }

    private MatchView toView(MatchRecord match, String kind, RoundView round) {
        return new MatchView(
                match.id(), match.tournamentId(), kind, match.roundNo(), match.slotNo(),
                match.stage(), match.status(), match.player1UserId(), match.player1Nickname(),
                match.player2UserId(), match.player2Nickname(), match.player1Score(),
                match.player2Score(), match.winnerUserId(), match.nextMatchId(),
                match.createdAt(), match.startedAt(), match.finishedAt(), round);
    }

    private String tournamentKind(MatchRecord match) {
        return tournaments.findById(match.tournamentId())
                .map(TournamentRecord::kind)
                .orElse(TournamentService.KIND_TOURNAMENT);
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
