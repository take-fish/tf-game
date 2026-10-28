package com.fishmatrix.game.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.game.arena.ArenaGames;
import com.fishmatrix.game.arena.ExerciseEngine;
import com.fishmatrix.game.arena.ExerciseEngine.ExerciseState;
import com.fishmatrix.game.arena.ExerciseEngine.PlayerSeed;
import com.fishmatrix.game.arena.ExerciseInput;
import com.fishmatrix.game.config.ExerciseProperties;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.store.ExerciseRepository;
import com.fishmatrix.game.store.ExerciseRepository.MemberRow;
import com.fishmatrix.game.store.ExerciseRepository.ResultRow;
import com.fishmatrix.game.store.ExerciseRepository.RoomRow;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 集群巡检对抗调度：单实例内存房间，25Hz 积分、12.5Hz 广播、1 秒快照。
 *
 * <p>赛事小局以 roundId 作为房间 ID，自动开局；休闲房间由 REST/WS 创建并等待全员准备。
 * 结算复用 {@link com.fishmatrix.game.arena.ArenaRoundFinishedEvent}，
 * 因此页面淘汰、BO 计分与晋级逻辑完全一致。</p>
 *
 * <p>房间状态见 {@link ExerciseRoomRegistry}，视图推送见 {@link ExerciseBroadcaster}，
 * 开局与结算见 {@link ExerciseRoomLifecycle}。</p>
 */
@Slf4j
@Service
public class ExerciseService {

    static final String STATUS_WAITING = "WAITING";
    static final String STATUS_RUNNING = "RUNNING";
    static final String STATUS_FINISHED = "FINISHED";
    static final String SOURCE_CASUAL = "CASUAL";
    static final String SOURCE_TOURNAMENT = "TOURNAMENT";
    private static final long WAITING_TTL_MILLIS = 5 * 60 * 1000L;

    private final ExerciseEngine engine;
    private final ExerciseProperties properties;
    private final ExerciseRepository repository;
    private final ExerciseRoomRegistry rooms;
    private final ExerciseBroadcaster broadcaster;
    private final ExercisePersister persister;
    private final ExerciseRoomLifecycle lifecycle;

    @Autowired
    public ExerciseService(
            ExerciseEngine engine,
            ExerciseProperties properties,
            ExerciseRepository repository,
            ExerciseRoomRegistry rooms,
            ExerciseBroadcaster broadcaster,
            ExercisePersister persister,
            ExerciseRoomLifecycle lifecycle) {
        this.engine = engine;
        this.properties = properties;
        this.repository = repository;
        this.rooms = rooms;
        this.broadcaster = broadcaster;
        this.persister = persister;
        this.lifecycle = lifecycle;
    }

    /** 单元测试用：用原始依赖就地组装各协作组件。 */
    ExerciseService(
            ExerciseEngine engine,
            ExerciseProperties properties,
            ExerciseRepository repository,
            GameSessionRegistry registry,
            ApplicationEventPublisher publisher,
            ObjectMapper objectMapper) {
        this.engine = engine;
        this.properties = properties;
        this.repository = repository;
        this.rooms = new ExerciseRoomRegistry();
        this.broadcaster = new ExerciseBroadcaster(registry, engine, objectMapper);
        this.persister = new ExercisePersister(repository, objectMapper);
        this.lifecycle = new ExerciseRoomLifecycle(
                engine, properties, repository, publisher, objectMapper,
                this.rooms, this.broadcaster, this.persister);
    }

    public boolean supports(String gameId) {
        return ArenaGames.CLUSTER_PATROL.equals(gameId);
    }

    /** 赛事开赛：直接以 roundId 建房间并开始，无准备流程。 */
    public StartState startTournament(String matchId, String roundId, List<PlayerSeed> seeds,
                                      long now) {
        Room room = new Room(
                roundId, newRoomCode(), ExerciseEngine.MODE_TEAM, SOURCE_TOURNAMENT,
                matchId, seeds.isEmpty() ? null : seeds.get(0).userId(),
                seeds.size(), properties.roundSeconds(), now);
        for (PlayerSeed seed : seeds) {
            room.members.put(seed.userId(), new Member(
                    seed.userId(), seed.nickname(), seed.team(), true, seed.bot(), now));
        }
        rooms.rooms.put(room.id, room);
        synchronized (room.lock) {
            lifecycle.start(room, now);
            return new StartState(persister.writeState(room.state), room.state.endsAt);
        }
    }

    public void closeRoom(String roomId) {
        rooms.rooms.remove(roomId);
    }

    public Map<String, Object> create(GameIdentity user, String mode, Integer maxPlayers) {
        requireEnabled();
        recover();
        long now = System.currentTimeMillis();
        sweep(now);
        String normalized = normalizeMode(mode);
        if (rooms.activeCasualRoomOf(user.userId()) != null) {
            throw GameException.conflict("GAME_EXERCISE_IN_ROOM", "你已在一个巡检房间中");
        }
        if (rooms.rooms.size() >= properties.maxRooms()) {
            throw GameException.conflict("GAME_EXERCISE_FULL", "巡检房间已满，请稍后再试");
        }
        int seats = ExerciseEngine.MODE_TRAINING.equals(normalized)
                ? 1 : clamp(maxPlayers == null ? 4 : maxPlayers, 2, properties.maxPlayers());
        Room room = new Room(
                UUID.randomUUID().toString(), newRoomCode(), normalized, SOURCE_CASUAL,
                null, user.userId(), seats, properties.roundSeconds(), now);
        room.members.put(user.userId(), new Member(
                user.userId(), user.nickname(), null, true, false, now));
        rooms.rooms.put(room.id, room);
        synchronized (room.lock) {
            repository.insertRoom(ExercisePersister.row(room, null));
            room.persisted = true;
            for (Member member : room.members.values()) {
                repository.insertMember(ExercisePersister.memberRow(room.id, member));
            }
            if (ExerciseEngine.MODE_TRAINING.equals(normalized)) {
                lifecycle.start(room, now);
            } else {
                broadcaster.broadcastRoom(room);
            }
            return broadcaster.roomView(room, user.userId());
        }
    }

    public List<Map<String, Object>> listRooms(GameIdentity user) {
        if (!properties.enabled()) {
            return List.of();
        }
        recover();
        String joined = rooms.activeCasualRoomOf(user.userId());
        return rooms.rooms.values().stream()
                .filter(room -> SOURCE_CASUAL.equals(room.source))
                .filter(room -> STATUS_WAITING.equals(room.status)
                        || room.id.equals(joined))
                .sorted(Comparator.comparingLong((Room room) -> room.createdAt).reversed())
                .map(room -> broadcaster.roomView(room, user.userId()))
                .toList();
    }

    public Map<String, Object> detail(GameIdentity user, String key) {
        Room room = rooms.require(key);
        return broadcaster.roomView(room, user.userId());
    }

    public Map<String, Object> join(GameIdentity user, String key) {
        requireEnabled();
        recover();
        Room room = rooms.require(key);
        synchronized (room.lock) {
            if (!SOURCE_CASUAL.equals(room.source)
                    || ExerciseEngine.MODE_TRAINING.equals(room.mode)) {
                throw GameException.forbidden("该房间不接受加入");
            }
            if (room.members.containsKey(user.userId())) {
                return broadcaster.roomView(room, user.userId());
            }
            if (!STATUS_WAITING.equals(room.status)) {
                throw GameException.conflict("GAME_EXERCISE_STARTED", "巡检已经开始");
            }
            if (humans(room) >= room.maxPlayers) {
                throw GameException.conflict("GAME_EXERCISE_FULL", "房间人数已满");
            }
            Member member = new Member(
                    user.userId(), user.nickname(), null, false, false,
                    System.currentTimeMillis());
            room.members.put(user.userId(), member);
            repository.insertMember(ExercisePersister.memberRow(room.id, member));
            broadcaster.broadcastRoom(room);
            return broadcaster.roomView(room, user.userId());
        }
    }

    public Map<String, Object> ready(GameIdentity user, String key) {
        requireEnabled();
        Room room = rooms.require(key);
        synchronized (room.lock) {
            Member member = requireMember(room, user.userId());
            if (!STATUS_WAITING.equals(room.status)) {
                return broadcaster.roomView(room, user.userId());
            }
            member.ready = true;
            repository.updateMemberReady(room.id, member.userId, true);
            if (humans(room) >= 2 && room.members.values().stream()
                    .filter(entry -> !entry.bot && entry.leftAt == 0)
                    .allMatch(entry -> entry.ready)) {
                lifecycle.start(room, System.currentTimeMillis());
            } else {
                broadcaster.broadcastRoom(room);
            }
            return broadcaster.roomView(room, user.userId());
        }
    }

    public void leave(GameIdentity user, String key) {
        Room room = rooms.find(key);
        if (room == null) {
            return;
        }
        synchronized (room.lock) {
            Member member = room.members.get(user.userId());
            if (member == null) {
                return;
            }
            long now = System.currentTimeMillis();
            member.leftAt = now;
            if (room.persisted) {
                repository.updateMemberLeft(room.id, member.userId, now);
            }
            if (STATUS_WAITING.equals(room.status)) {
                room.members.remove(member.userId);
                if (user.userId().equals(room.ownerUserId)) {
                    room.ownerUserId = room.members.keySet().stream().findFirst().orElse(null);
                }
                if (room.members.isEmpty()) {
                    rooms.rooms.remove(room.id, room);
                    if (room.persisted) {
                        repository.cancelRoom(room.id, "房主离开", now);
                    }
                    return;
                }
                broadcaster.broadcastRoom(room);
                return;
            }
            if (STATUS_RUNNING.equals(room.status) && room.state != null) {
                room.state.actors.removeIf(actor -> !actor.bot
                        && user.userId().equals(actor.userId));
                room.state.players.remove(user.userId());
                if (humanActors(room.state) == 0) {
                    lifecycle.cancel(room, now, "所有成员已离开");
                    return;
                }
                broadcaster.broadcastState(room);
            }
        }
    }

    /** 断线只清理等待中的休闲房间；进行中的对局交给比赛断线判负逻辑。 */
    public void onDisconnect(String userId) {
        for (Room room : List.copyOf(rooms.rooms.values())) {
            if (!SOURCE_CASUAL.equals(room.source)
                    || !STATUS_WAITING.equals(room.status)) {
                continue;
            }
            if (!room.members.containsKey(userId)) {
                continue;
            }
            synchronized (room.lock) {
                Member member = room.members.remove(userId);
                if (member != null && room.persisted) {
                    repository.updateMemberLeft(
                            room.id, userId, System.currentTimeMillis());
                }
                if (room.members.isEmpty()) {
                    rooms.rooms.remove(room.id, room);
                    if (room.persisted) {
                        repository.cancelRoom(
                                room.id, "成员断线", System.currentTimeMillis());
                    }
                } else {
                    if (userId.equals(room.ownerUserId)) {
                        room.ownerUserId = room.members.keySet().stream()
                                .findFirst().orElse(null);
                    }
                    broadcaster.broadcastRoom(room);
                }
            }
        }
    }

    public void input(GameIdentity user, String key, ExerciseInput input) {
        requireEnabled();
        Room room = rooms.require(key);
        synchronized (room.lock) {
            if (!STATUS_RUNNING.equals(room.status) || room.state == null) {
                throw GameException.conflict("GAME_EXERCISE_NOT_RUNNING", "巡检尚未开始");
            }
            Member member = requireMember(room, user.userId());
            if (member.bot) {
                throw GameException.forbidden("训练靶不接受操作");
            }
            long now = System.currentTimeMillis();
            rateLimit(room, user.userId(), now);
            engine.input(room.state, user.userId(), input, now);
            if (now - room.lastBroadcastAt >= properties.broadcastMillis()) {
                broadcaster.broadcastState(room);
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.game.exercise.tick-millis:40}")
    public void tick() {
        if (!properties.enabled()) {
            return;
        }
        recover();
        long now = System.currentTimeMillis();
        if (now - rooms.lastSweepAt >= 2000) {
            rooms.lastSweepAt = now;
            sweep(now);
        }
        for (Room room : List.copyOf(rooms.rooms.values())) {
            if (rooms.rooms.get(room.id) != room) {
                continue;
            }
            synchronized (room.lock) {
                if (rooms.rooms.get(room.id) != room || !STATUS_RUNNING.equals(room.status)
                        || room.state == null) {
                    continue;
                }
                try {
                    engine.tick(room.state, now);
                } catch (Exception exception) {
                    log.warn("巡检 tick 失败 room={}", room.id, exception);
                    continue;
                }
                if (room.state.finished) {
                    lifecycle.finish(room, now);
                    continue;
                }
                if (now - room.lastSnapshotAt >= properties.snapshotSeconds() * 1000L) {
                    persister.persistState(room);
                }
                if (now - room.lastBroadcastAt >= properties.broadcastMillis()) {
                    broadcaster.broadcastState(room);
                }
            }
        }
    }

    public List<ResultRow> recentResults(int limit) {
        return repository.listRecentResults(clamp(limit, 1, 100));
    }

    private void rateLimit(Room room, String userId, long now) {
        Deque<Long> times = room.inputTimes.computeIfAbsent(userId, key -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > 1000) {
            times.pollFirst();
        }
        if (times.size() >= properties.inputsPerSecond()) {
            throw GameException.conflict("GAME_RATE_LIMITED", "操作太频繁，请稍后再试");
        }
        times.addLast(now);
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw GameException.conflict("GAME_EXERCISE_DISABLED", "巡检演练尚未启用");
        }
    }

    private static Member requireMember(Room room, String userId) {
        Member member = room.members.get(userId);
        if (member == null || member.leftAt != 0) {
            throw GameException.forbidden("只有房间成员可以操作");
        }
        return member;
    }

    private static int humans(Room room) {
        return (int) room.members.values().stream()
                .filter(member -> !member.bot && member.leftAt == 0)
                .count();
    }

    private static int humanActors(ExerciseState state) {
        return (int) state.actors.stream().filter(actor -> !actor.bot).count();
    }

    private void sweep(long now) {
        for (Room room : List.copyOf(rooms.rooms.values())) {
            if (SOURCE_CASUAL.equals(room.source)
                    && STATUS_WAITING.equals(room.status)
                    && now - room.createdAt > WAITING_TTL_MILLIS) {
                synchronized (room.lock) {
                    if (rooms.rooms.remove(room.id, room) && room.persisted) {
                        repository.cancelRoom(room.id, "房间超时未开始", now);
                    }
                    broadcaster.sendToRoom(room, Map.of(
                            "type", "exercise_finished",
                            "roomId", room.id,
                            "roomCode", room.roomCode,
                            "reason", "房间超时未开始",
                            "standings", List.of()));
                }
            }
        }
    }

    private void recover() {
        if (!rooms.recovered.compareAndSet(false, true)) {
            return;
        }
        try {
            for (RoomRow row : repository.listRunningRooms()) {
                if (rooms.rooms.containsKey(row.id())) {
                    continue;
                }
                ExerciseState state = persister.readState(row.stateJson());
                Room room = new Room(
                        row.id(), row.roomCode(), row.mode(), row.source(),
                        row.matchId(), row.ownerUserId(), row.maxPlayers(),
                        row.durationSeconds(), row.createdAt());
                room.status = STATUS_RUNNING;
                room.startedAt = row.startedAt() == null ? row.createdAt() : row.startedAt();
                room.state = state;
                room.persisted = true;
                room.lastSnapshotAt = System.currentTimeMillis();
                for (MemberRow member : repository.listMembers(row.id())) {
                    room.members.put(member.userId(), new Member(
                            member.userId(), member.nickname(), member.team(),
                            member.ready(), member.bot(), member.joinedAt()));
                }
                rooms.rooms.put(room.id, room);
                if (state.finished) {
                    synchronized (room.lock) {
                        lifecycle.finish(room, System.currentTimeMillis());
                    }
                } else {
                    log.info("恢复巡检房间 room={} mode={} seq={}",
                            room.id, room.mode, state.seq);
                }
            }
        } catch (Exception exception) {
            rooms.recovered.set(false);
            log.warn("巡检房间恢复失败", exception);
        }
    }

    private static String normalizeMode(String mode) {
        String value = mode == null ? "" : mode.strip().toUpperCase();
        return switch (value) {
            case ExerciseEngine.MODE_TEAM -> ExerciseEngine.MODE_TEAM;
            case ExerciseEngine.MODE_TRAINING -> ExerciseEngine.MODE_TRAINING;
            case ExerciseEngine.MODE_FFA, "" -> ExerciseEngine.MODE_FFA;
            default -> throw GameException.badRequest(
                    "GAME_FRAME_INVALID", "未知的巡检模式");
        };
    }

    private static String newRoomCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder(6);
        for (int index = 0; index < 6; index += 1) {
            code.append(alphabet.charAt(
                    ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return code.toString();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record StartState(String stateJson, long endsAt) {
    }

    static final class Room {
        final String id;
        final String roomCode;
        final String mode;
        final String source;
        final String matchId;
        String ownerUserId;
        final int maxPlayers;
        final int durationSeconds;
        final long createdAt;
        final Object lock = new Object();
        final Map<String, Member> members = new LinkedHashMap<>();
        final Map<String, Deque<Long>> inputTimes = new LinkedHashMap<>();
        ExerciseState state;
        String status = STATUS_WAITING;
        boolean persisted;
        long startedAt;
        long lastBroadcastAt;
        long lastSnapshotAt;

        Room(String id, String roomCode, String mode, String source,
             String matchId, String ownerUserId, int maxPlayers,
             int durationSeconds, long createdAt) {
            this.id = id;
            this.roomCode = roomCode;
            this.mode = mode;
            this.source = source;
            this.matchId = matchId;
            this.ownerUserId = ownerUserId;
            this.maxPlayers = maxPlayers;
            this.durationSeconds = durationSeconds;
            this.createdAt = createdAt;
        }
    }

    static final class Member {
        final String userId;
        final String nickname;
        String team;
        boolean ready;
        final boolean bot;
        final long joinedAt;
        long leftAt;

        Member(String userId, String nickname, String team,
               boolean ready, boolean bot, long joinedAt) {
            this.userId = userId;
            this.nickname = nickname;
            this.team = team;
            this.ready = ready;
            this.bot = bot;
            this.joinedAt = joinedAt;
        }
    }
}
