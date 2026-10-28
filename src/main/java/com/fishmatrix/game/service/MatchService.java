package com.fishmatrix.game.service;

import com.fishmatrix.game.arena.ArenaRoundFinishedEvent;
import com.fishmatrix.game.arena.ArenaService;
import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.converter.MatchConverters;
import com.fishmatrix.game.converter.SqlTime;
import com.fishmatrix.game.mapper.GameBlindPieceMapper;
import com.fishmatrix.game.mapper.MatchMapper;
import com.fishmatrix.game.mapper.TournamentMapper;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.model.RoundRecord;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p>本类负责对外的准备、认输、超时巡检与查询；落子、开局和结算分别由
 * {@link MatchMoveService}、{@link MatchRoundService}、{@link MatchSettlementService} 完成。
 */
@Slf4j
@Service
public class MatchService {

    private final MatchMapper matches;
    private final TournamentMapper tournaments;
    private final GameSessionRegistry registry;
    private final GameProperties properties;
    private final MatchSessionState state;
    private final MatchViewAssembler assembler;
    private final MatchBroadcaster broadcaster;
    private final MatchRoundService rounds;
    private final MatchMoveService moves;
    private final MatchSettlementService settlement;

    @Autowired
    public MatchService(
            MatchMapper matches,
            TournamentMapper tournaments,
            GameSessionRegistry registry,
            GameProperties properties,
            MatchSessionState state,
            MatchViewAssembler assembler,
            MatchBroadcaster broadcaster,
            MatchRoundService rounds,
            MatchMoveService moves,
            MatchSettlementService settlement) {
        this.matches = matches;
        this.tournaments = tournaments;
        this.registry = registry;
        this.properties = properties;
        this.state = state;
        this.assembler = assembler;
        this.broadcaster = broadcaster;
        this.rounds = rounds;
        this.moves = moves;
        this.settlement = settlement;
    }

    /** 单元测试用：用原始依赖就地组装各协作组件。 */
    MatchService(
            MatchMapper matches,
            TournamentMapper tournaments,
            GameBlindPieceMapper blindPieces,
            GameSessionRegistry registry,
            GameNotificationService notifications,
            ApplicationEventPublisher publisher,
            GameProperties properties,
            ArenaService arena,
            ExerciseService exercise) {
        this.matches = matches;
        this.tournaments = tournaments;
        this.registry = registry;
        this.properties = properties;
        this.state = new MatchSessionState();
        this.assembler = new MatchViewAssembler(matches, tournaments, blindPieces, arena);
        this.broadcaster = new MatchBroadcaster(registry, this.assembler);
        this.settlement = new MatchSettlementService(
                matches, tournaments, publisher, properties,
                arena, exercise, this.state, this.broadcaster);
        this.rounds = new MatchRoundService(
                matches, tournaments, blindPieces, arena, exercise, notifications,
                registry, this.state, this.broadcaster, this.assembler);
        this.moves = new MatchMoveService(
                matches, tournaments, blindPieces, this.broadcaster,
                this.assembler, this.settlement);
    }

    public MatchView detail(String matchId) {
        return assembler.detail(MatchGuards.requireMatch(matches, matchId));
    }

    /** 约战详情只对双方开放，普通赛事保持公开观战。 */
    public MatchView detailForUser(GameIdentity user, String matchId) {
        MatchRecord match = MatchGuards.requireMatch(matches, matchId);
        TournamentRecord tournament = assembler.tournamentOf(match);
        String kind = tournament == null ? TournamentService.KIND_TOURNAMENT : tournament.kind();
        if (TournamentService.KIND_DUEL.equals(kind)
                && !MatchGuards.isParticipant(match, user.userId())) {
            throw GameException.forbidden("约战仅双方可见");
        }
        return assembler.detail(match);
    }

    public List<MatchView> activeForUser(String userId) {
        return MatchConverters.records(matches.selectActiveByUser(userId)).stream()
                .map(match -> assembler.toView(match, assembler.tournamentOf(match), null))
                .toList();
    }

    @Transactional
    public MatchView ready(GameIdentity user, String matchId) {
        MatchRecord match = MatchGuards.requireParticipant(matches, user, matchId);
        if ("READY".equals(match.status())) {
            Set<String> ready = state.ready(matchId);
            ready.add(user.userId());
            if (ready.contains(match.player1UserId()) && ready.contains(match.player2UserId())) {
                state.clearReady(matchId);
                rounds.startFirstGame(match);
            } else {
                broadcaster.send(match, Map.of(
                        "type", "match_ready",
                        "matchId", matchId,
                        "readyUserIds", List.copyOf(ready)));
            }
            return detail(matchId);
        }
        if ("RUNNING".equals(match.status())) {
            RoundRecord round = findLatestRound(matchId);
            if (round == null || !"REST".equals(round.status())) {
                throw GameException.conflict("GAME_MATCH_STARTED", "比赛已经开始");
            }
            Set<String> ready = state.ready(matchId);
            ready.add(user.userId());
            matches.updateReadyUserIds(round.id(), String.join(",", ready));
            if (ready.contains(match.player1UserId()) && ready.contains(match.player2UserId())) {
                state.clearReady(matchId);
                rounds.startNextGame(matchId);
                return detail(matchId);
            }
            broadcaster.broadcastMatch(match);
            return detail(matchId);
        }
        throw GameException.conflict("GAME_MATCH_CLOSED", "比赛已结束");
    }

    @Transactional
    public MatchView placeStone(GameIdentity user, String matchId, Integer x, Integer y) {
        return moves.placeStone(user, matchId, x, y);
    }

    /** 盲棋翻棋：花一步揭开一枚反面棋，翻完即换对方行动。 */
    @Transactional
    public MatchView revealPiece(GameIdentity user, String matchId, Integer x, Integer y) {
        return moves.revealPiece(user, matchId, x, y);
    }

    /** 盲棋走子：已翻开的己方棋按中国象棋规则移动或吃子。 */
    @Transactional
    public MatchView movePiece(GameIdentity user, String matchId,
                               Integer fromX, Integer fromY, Integer toX, Integer toY) {
        return moves.movePiece(user, matchId, fromX, fromY, toX, toY);
    }

    @Transactional
    public MatchView resign(GameIdentity user, String matchId) {
        MatchRecord match = MatchGuards.requireParticipant(matches, user, matchId);
        if ("FINISHED".equals(match.status()) || "BYE".equals(match.status())) {
            throw GameException.conflict("GAME_MATCH_CLOSED", "比赛已结束");
        }
        String opponent = user.userId().equals(match.player1UserId())
                ? match.player2UserId() : match.player1UserId();
        long now = System.currentTimeMillis();
        RoundRecord round = findLatestRound(matchId);

        if ("READY".equals(match.status()) || round == null) {
            if (matches.finishActive(match.id(), opponent,
                    match.player1Score(), match.player2Score(), SqlTime.toDateTime(now)) == 0) {
                return detail(matchId);
            }
            settlement.finishByForfeit(match, opponent, "认输");
            return detail(matchId);
        }
        if ("REST".equals(round.status())) {
            if (matches.finishActive(match.id(), opponent,
                    match.player1Score(), match.player2Score(), SqlTime.toDateTime(now)) == 0) {
                return detail(matchId);
            }
            matches.finishRestRound(round.id(), SqlTime.toDateTime(now));
            settlement.finishByForfeit(match, opponent, "认输");
            return detail(matchId);
        }
        if ("RUNNING".equals(round.status())) {
            settlement.finishGame(match, round, opponent, "认输");
            return detail(matchId);
        }
        throw GameException.conflict("GAME_MATCH_CLOSED", "比赛已结束");
    }

    /** 双方都点了“立即开始”，或 60 秒休息结束，进入下一小局。 */
    @Transactional
    public void startNextGame(String matchId) {
        rounds.startNextGame(matchId);
    }

    /** 每秒检查：休息到期自动开局、每步超时、断线超时。 */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void tick() {
        long now = System.currentTimeMillis();
        for (RoundRecord round : MatchConverters.roundRecords(
                matches.selectDueRest(SqlTime.toDateTime(now)))) {
            try {
                rounds.startNextGame(round.matchId());
            } catch (Exception exception) {
                log.warn("自动开始下一局失败 match={}", round.matchId(), exception);
            }
        }
        long moveDeadline = properties.moveTimeoutSeconds() * 1000L;
        for (RoundRecord round : MatchConverters.roundRecords(matches.selectRunningRounds())) {
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
        MatchRecord match = findMatch(round.matchId());
        if (match == null || !"RUNNING".equals(match.status())
                || round.turnUserId() == null) {
            return;
        }
        String winner = round.turnUserId().equals(match.player1UserId())
                ? match.player2UserId() : match.player1UserId();
        settlement.finishGame(match, round, winner, "超时判负");
    }

    public void onConnect(String userId) {
        state.markConnected(userId);
    }

    public void onDisconnect(String userId) {
        for (MatchRecord match : MatchConverters.records(matches.selectActiveByUser(userId))) {
            if ("RUNNING".equals(match.status())) {
                state.markDisconnect(match.id(), userId);
            }
        }
    }

    private void checkDisconnects(long now) {
        long deadline = properties.disconnectTimeoutSeconds() * 1000L;
        for (String key : state.disconnectKeys()) {
            Long since = state.disconnectSince(key);
            if (since == null || now - since < deadline) {
                continue;
            }
            int separator = key.indexOf('|');
            if (separator <= 0) {
                state.clearDisconnect(key);
                continue;
            }
            String matchId = key.substring(0, separator);
            String userId = key.substring(separator + 1);
            MatchRecord match = findMatch(matchId);
            if (match == null || !"RUNNING".equals(match.status())) {
                state.clearDisconnect(key);
                continue;
            }
            if (!registry.byUser(userId).isEmpty()) {
                state.clearDisconnect(key);
                continue;
            }
            RoundRecord round = findLatestRound(matchId);
            if (round == null) {
                state.clearDisconnect(key);
                continue;
            }
            if (!"RUNNING".equals(round.status())) {
                // 休息中不断言，等下一局开始后继续计时。
                continue;
            }
            String winner = userId.equals(match.player1UserId())
                    ? match.player2UserId() : match.player1UserId();
            settlement.finishGame(match, round, winner, "断线判负");
        }
    }

    /** 竞技场引擎结算后统一走原有 BO 计分、休息与晋级流程。 */
    @EventListener
    @Transactional
    public void onArenaRoundFinished(ArenaRoundFinishedEvent event) {
        MatchRecord match = findMatch(event.matchId());
        if (match == null || !"RUNNING".equals(match.status())) {
            return;
        }
        RoundRecord round = Optional.ofNullable(matches.selectRound(event.roundId()))
                .map(MatchConverters::toRecord)
                .orElse(null);
        if (round == null || !"RUNNING".equals(round.status())) {
            return;
        }
        settlement.finishGame(match, round, event.winnerUserId(), event.reason());
        settlement.closeRoundResources(event.roundId());
    }

    private MatchRecord findMatch(String matchId) {
        return Optional.ofNullable(matches.selectById(matchId))
                .map(MatchConverters::toRecord)
                .orElse(null);
    }

    private RoundRecord findLatestRound(String matchId) {
        return Optional.ofNullable(matches.selectLatestRound(matchId))
                .map(MatchConverters::toRecord)
                .orElse(null);
    }
}
