package com.fishmatrix.game.arena;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.game.config.ArenaProperties;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.RoundRecord;
import com.fishmatrix.game.store.MatchRepository;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 实时竞技场调度：200ms tick、4Hz 广播、每秒快照、动作限流与幂等。
 * 内存中的 state 是权威副本，state_json 用于服务重启后恢复。
 */
@Slf4j
@Service
public class ArenaService {

    private static final long BROADCAST_INTERVAL_MILLIS = 250;
    private static final int RECENT_ACTION_LIMIT = 64;

    private final MatchRepository matches;
    private final GameSessionRegistry registry;
    private final ArenaProperties properties;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final Map<String, ArenaEngine<? extends ArenaState>> engines = new LinkedHashMap<>();
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private final AtomicBoolean recovered = new AtomicBoolean(false);
    private volatile long lastDiscoveryAt;

    public ArenaService(
            MatchRepository matches,
            GameSessionRegistry registry,
            ArenaProperties properties,
            ApplicationEventPublisher publisher,
            ObjectMapper objectMapper,
            List<ArenaEngine<? extends ArenaState>> engineList) {
        this.matches = matches;
        this.registry = registry;
        this.properties = properties;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        for (ArenaEngine<? extends ArenaState> engine : engineList) {
            engines.put(engine.gameId(), engine);
        }
    }

    public boolean supports(String gameId) {
        return engines.containsKey(gameId);
    }

    public int restSeconds() {
        return properties.restSeconds();
    }

    /** 开赛时由 MatchService 调用：生成种子状态并返回写入 game_round 的快照。 */
    public StartState startState(String gameId, List<String> players, String attackerUserId,
                                 long now, String roundId) {
        ArenaEngine<? extends ArenaState> engine = engines.get(gameId);
        if (engine == null) {
            throw GameException.badRequest("GAME_ARENA_UNKNOWN", "未知的演练剧本");
        }
        long endsAt = now + engine.roundMillis();
        ArenaContext context = new ArenaContext(
                gameId, List.copyOf(players), attackerUserId, now, endsAt,
                Integer.toUnsignedLong(roundId.hashCode()));
        ArenaState state = engine.create(context);
        return new StartState(writeState(state), endsAt);
    }

    /** 详情/重连时把 state_json 还原为前端可渲染的 Map。 */
    public Map<String, Object> stateView(RoundRecord round) {
        if (round == null || !ArenaGames.isArena(round.gameId())) {
            return null;
        }
        if (round.stateJson() == null || round.stateJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(round.stateJson(), Map.class);
        } catch (Exception exception) {
            log.warn("竞技场状态解析失败 round={}", round.id(), exception);
            return null;
        }
    }

    public void closeRound(String roundId) {
        slots.remove(roundId);
    }

    /** WebSocket arena_action 入口：校验身份、限流、幂等后交给引擎。 */
    public void action(GameIdentity user, String matchId, ArenaAction action) {
        if (!properties.enabled()) {
            throw GameException.conflict("GAME_ARENA_DISABLED", "实时竞技场尚未启用");
        }
        recover();
        MatchRecord match = matches.findById(matchId)
                .orElseThrow(() -> GameException.notFound("比赛不存在"));
        if (!"RUNNING".equals(match.status())) {
            throw GameException.conflict("GAME_MATCH_NOT_RUNNING", "比赛尚未开始");
        }
        if (!isParticipant(match, user.userId())) {
            throw GameException.forbidden("只有参赛选手可以操作本局");
        }
        RoundRecord round = matches.latestRound(matchId)
                .orElseThrow(() -> GameException.conflict("GAME_ROUND_MISSING", "对局尚未开始"));
        if (!"RUNNING".equals(round.status())) {
            throw GameException.conflict("GAME_ROUND_NOT_RUNNING", "当前小局不在进行中");
        }
        Slot slot = slot(round, match);
        if (slot == null) {
            throw GameException.conflict("GAME_ROUND_NOT_RUNNING", "当前小局不在进行中");
        }
        long now = System.currentTimeMillis();
        synchronized (slot.lock) {
            if (slot.state.finished) {
                throw GameException.conflict("GAME_ARENA_FINISHED", "本局已经结束");
            }
            String actionKey = action.actionId() == null || action.actionId().isBlank()
                    ? null : user.userId() + "|" + action.actionId().strip();
            if (actionKey != null && slot.recentActions.containsKey(actionKey)) {
                return;
            }
            rateLimit(slot, user.userId(), now);
            applyUnchecked(slot.engine, slot.state, action, user.userId(), now);
            if (actionKey != null) {
                slot.recentActions.put(actionKey, now);
                while (slot.recentActions.size() > RECENT_ACTION_LIMIT) {
                    slot.recentActions.remove(slot.recentActions.keySet().iterator().next());
                }
            }
            slot.state.seq += 1;
            persist(slot);
            broadcast(slot);
            if (slot.state.finished) {
                finish(slot, now);
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.game.arena.tick-millis:200}")
    public void tick() {
        if (!properties.enabled()) {
            return;
        }
        recover();
        long now = System.currentTimeMillis();
        if (now - lastDiscoveryAt >= 2000) {
            lastDiscoveryAt = now;
            scanRunningRounds();
        }
        for (Slot slot : List.copyOf(slots.values())) {
            if (slots.get(slot.roundId) != slot) {
                continue;
            }
            synchronized (slot.lock) {
                if (!slots.containsKey(slot.roundId)) {
                    continue;
                }
                if (slot.state.finished) {
                    finish(slot, now);
                    continue;
                }
                try {
                    tickUnchecked(slot.engine, slot.state, now);
                } catch (Exception exception) {
                    log.warn("竞技场 tick 失败 round={}", slot.roundId, exception);
                    continue;
                }
                if (slot.state.finished) {
                    slot.state.seq += 1;
                    persist(slot);
                    broadcast(slot);
                    finish(slot, now);
                    continue;
                }
                if (now - slot.lastSnapshotAt >= properties.snapshotSeconds() * 1000L) {
                    persist(slot);
                }
                if (now - slot.lastBroadcastAt >= BROADCAST_INTERVAL_MILLIS) {
                    broadcast(slot);
                }
            }
        }
    }

    private void finish(Slot slot, long now) {
        slots.remove(slot.roundId, slot);
        Map<String, Object> frame = frame(slot);
        frame.put("type", "arena_finished");
        registry.broadcastMatch(slot.matchId, slot.participants, frame);
        try {
            publisher.publishEvent(new ArenaRoundFinishedEvent(
                    slot.matchId, slot.roundId,
                    slot.state.winnerUserId, slot.state.finishReason));
        } catch (Exception exception) {
            // 结算失败时小局仍保持 RUNNING，2 秒内的发现扫描会重新挂载并再次结算。
            log.error("竞技场小局结算失败 round={}", slot.roundId, exception);
            return;
        }
        log.info("竞技场小局结束 round={} winner={} reason={}", slot.roundId,
                slot.state.winnerUserId, slot.state.finishReason);
    }

    private void persist(Slot slot) {
        try {
            matches.updateArenaState(
                    slot.roundId, writeState(slot.state), slot.state.seq, slot.state.seq);
            slot.lastSnapshotAt = System.currentTimeMillis();
        } catch (Exception exception) {
            log.warn("竞技场快照写入失败 round={}", slot.roundId, exception);
        }
    }

    private void broadcast(Slot slot) {
        registry.broadcastMatch(slot.matchId, slot.participants, frame(slot));
        slot.lastBroadcastAt = System.currentTimeMillis();
    }

    private Map<String, Object> frame(Slot slot) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "arena_state");
        frame.put("matchId", slot.matchId);
        frame.put("roundId", slot.roundId);
        frame.put("gameId", slot.state.gameId);
        frame.put("seq", slot.state.seq);
        frame.put("serverTime", System.currentTimeMillis());
        frame.put("state", slot.state);
        return frame;
    }

    private void rateLimit(Slot slot, String userId, long now) {
        Deque<Long> times = slot.actionTimes.computeIfAbsent(
                userId, key -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > 1000) {
            times.pollFirst();
        }
        if (times.size() >= properties.actionsPerSecond()) {
            throw GameException.conflict("GAME_RATE_LIMITED", "操作太频繁，请稍后再试");
        }
        times.addLast(now);
    }

    private Slot slot(RoundRecord round, MatchRecord match) {
        Slot existing = slots.get(round.id());
        if (existing != null) {
            return existing;
        }
        if (round.stateJson() == null || round.stateJson().isBlank()) {
            return null;
        }
        ArenaEngine<? extends ArenaState> engine = engines.get(round.gameId());
        if (engine == null) {
            return null;
        }
        try {
            ArenaState state = objectMapper.readValue(round.stateJson(), engine.stateType());
            Slot created = new Slot(
                    round.id(), round.matchId(), engine, state, participants(match));
            created.state.seq = Math.max(created.state.seq, round.stateVersion());
            Slot raced = slots.putIfAbsent(round.id(), created);
            return raced == null ? created : raced;
        } catch (Exception exception) {
            log.warn("竞技场状态恢复失败 round={}", round.id(), exception);
            return null;
        }
    }

    private void recover() {
        if (!recovered.compareAndSet(false, true)) {
            return;
        }
        scanRunningRounds();
    }

    /** 新开的小局与重启后的存量小局都通过该扫描进入内存调度。 */
    private void scanRunningRounds() {
        try {
            for (RoundRecord round : matches.listRunningArenaRounds(ArenaGames.GOMOKU)) {
                if (slots.containsKey(round.id())) {
                    continue;
                }
                MatchRecord match = matches.findById(round.matchId()).orElse(null);
                if (match == null || !"RUNNING".equals(match.status())) {
                    continue;
                }
                Slot slot = slot(round, match);
                if (slot != null) {
                    log.info("接入竞技场小局 round={} game={} seq={}",
                            round.id(), round.gameId(), slot.state.seq);
                }
            }
        } catch (Exception exception) {
            recovered.set(false);
            log.warn("竞技场恢复扫描失败", exception);
        }
    }

    private String writeState(ArenaState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception exception) {
            throw GameException.unavailable("竞技场状态序列化失败");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void applyUnchecked(ArenaEngine engine, ArenaState state,
                                       ArenaAction action, String userId, long now) {
        engine.apply(state, action, userId, now);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void tickUnchecked(ArenaEngine engine, ArenaState state, long now) {
        engine.tick(state, now);
    }

    private static boolean isParticipant(MatchRecord match, String userId) {
        return userId != null
                && (userId.equals(match.player1UserId()) || userId.equals(match.player2UserId()));
    }

    private static Set<String> participants(MatchRecord match) {
        Set<String> ids = new LinkedHashSet<>();
        if (match.player1UserId() != null) {
            ids.add(match.player1UserId());
        }
        if (match.player2UserId() != null) {
            ids.add(match.player2UserId());
        }
        return ids;
    }

    public record StartState(String stateJson, long endsAt) {
    }

    private static final class Slot {
        private final String roundId;
        private final String matchId;
        private final ArenaEngine<? extends ArenaState> engine;
        private final ArenaState state;
        private final Set<String> participants;
        private final Object lock = new Object();
        private final Map<String, Long> recentActions = new LinkedHashMap<>();
        private final Map<String, Deque<Long>> actionTimes = new LinkedHashMap<>();
        private long lastBroadcastAt;
        private long lastSnapshotAt;

        private Slot(String roundId, String matchId,
                     ArenaEngine<? extends ArenaState> engine, ArenaState state,
                     Set<String> participants) {
            this.roundId = roundId;
            this.matchId = matchId;
            this.engine = engine;
            this.state = state;
            this.participants = participants;
        }
    }
}
