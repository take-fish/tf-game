package com.fishmatrix.game.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.game.arena.ArenaGames;
import com.fishmatrix.game.arena.ArenaRoundFinishedEvent;
import com.fishmatrix.game.arena.ExerciseEngine;
import com.fishmatrix.game.arena.ExerciseEngine.ExercisePlayer;
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
import com.fishmatrix.game.ws.GameSession;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 集群巡检对抗调度：单实例内存房间，25Hz 积分、12.5Hz 广播、1 秒快照。
 *
 * <p>赛事小局以 roundId 作为房间 ID，自动开局；休闲房间由 REST/WS 创建并等待全员准备。
 * 结算复用 {@link ArenaRoundFinishedEvent}，因此页面淘汰、BO 计分与晋级逻辑完全一致。</p>
 */
@Service
public class ExerciseService {

    private static final Logger log = LoggerFactory.getLogger(ExerciseService.class);
    private static final String STATUS_WAITING = "WAITING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_FINISHED = "FINISHED";
    private static final String SOURCE_CASUAL = "CASUAL";
    private static final String SOURCE_TOURNAMENT = "TOURNAMENT";
    private static final long WAITING_TTL_MILLIS = 5 * 60 * 1000L;

    private final ExerciseEngine engine;
    private final ExerciseProperties properties;
    private final ExerciseRepository repository;
    private final GameSessionRegistry registry;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final AtomicBoolean recovered = new AtomicBoolean(false);
    private volatile long lastSweepAt;

    public ExerciseService(
            ExerciseEngine engine,
            ExerciseProperties properties,
            ExerciseRepository repository,
            GameSessionRegistry registry,
            ApplicationEventPublisher publisher,
            ObjectMapper objectMapper) {
        this.engine = engine;
        this.properties = properties;
        this.repository = repository;
        this.registry = registry;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
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
        rooms.put(room.id, room);
        synchronized (room.lock) {
            startLocked(room, now);
            return new StartState(writeState(room.state), room.state.endsAt);
        }
    }

    public void closeRoom(String roomId) {
        rooms.remove(roomId);
    }

    public Map<String, Object> create(GameIdentity user, String mode, Integer maxPlayers) {
        requireEnabled();
        recover();
        long now = System.currentTimeMillis();
        sweep(now);
        String normalized = normalizeMode(mode);
        if (activeCasualRoomOf(user.userId()) != null) {
            throw GameException.conflict("GAME_EXERCISE_IN_ROOM", "你已在一个巡检房间中");
        }
        if (rooms.size() >= properties.maxRooms()) {
            throw GameException.conflict("GAME_EXERCISE_FULL", "巡检房间已满，请稍后再试");
        }
        int seats = ExerciseEngine.MODE_TRAINING.equals(normalized)
                ? 1 : clamp(maxPlayers == null ? 4 : maxPlayers, 2, properties.maxPlayers());
        Room room = new Room(
                UUID.randomUUID().toString(), newRoomCode(), normalized, SOURCE_CASUAL,
                null, user.userId(), seats, properties.roundSeconds(), now);
        room.members.put(user.userId(), new Member(
                user.userId(), user.nickname(), null, true, false, now));
        rooms.put(room.id, room);
        synchronized (room.lock) {
            repository.insertRoom(row(room, null));
            room.persisted = true;
            for (Member member : room.members.values()) {
                repository.insertMember(memberRow(room.id, member));
            }
            if (ExerciseEngine.MODE_TRAINING.equals(normalized)) {
                startLocked(room, now);
            } else {
                broadcastRoom(room);
            }
            return roomView(room, user.userId());
        }
    }

    public List<Map<String, Object>> listRooms(GameIdentity user) {
        if (!properties.enabled()) {
            return List.of();
        }
        recover();
        String joined = activeCasualRoomOf(user.userId());
        return rooms.values().stream()
                .filter(room -> SOURCE_CASUAL.equals(room.source))
                .filter(room -> STATUS_WAITING.equals(room.status)
                        || room.id.equals(joined))
                .sorted(Comparator.comparingLong((Room room) -> room.createdAt).reversed())
                .map(room -> roomView(room, user.userId()))
                .toList();
    }

    public Map<String, Object> detail(GameIdentity user, String key) {
        Room room = requireRoom(key);
        return roomView(room, user.userId());
    }

    public Map<String, Object> join(GameIdentity user, String key) {
        requireEnabled();
        recover();
        Room room = requireRoom(key);
        synchronized (room.lock) {
            if (!SOURCE_CASUAL.equals(room.source)
                    || ExerciseEngine.MODE_TRAINING.equals(room.mode)) {
                throw GameException.forbidden("该房间不接受加入");
            }
            if (room.members.containsKey(user.userId())) {
                return roomView(room, user.userId());
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
            repository.insertMember(memberRow(room.id, member));
            broadcastRoom(room);
            return roomView(room, user.userId());
        }
    }

    public Map<String, Object> ready(GameIdentity user, String key) {
        requireEnabled();
        Room room = requireRoom(key);
        synchronized (room.lock) {
            Member member = requireMember(room, user.userId());
            if (!STATUS_WAITING.equals(room.status)) {
                return roomView(room, user.userId());
            }
            member.ready = true;
            repository.updateMemberReady(room.id, member.userId, true);
            if (humans(room) >= 2 && room.members.values().stream()
                    .filter(entry -> !entry.bot && entry.leftAt == 0)
                    .allMatch(entry -> entry.ready)) {
                startLocked(room, System.currentTimeMillis());
            } else {
                broadcastRoom(room);
            }
            return roomView(room, user.userId());
        }
    }

    public void leave(GameIdentity user, String key) {
        Room room = rooms.get(key);
        if (room == null) {
            room = findRoom(key);
        }
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
                    rooms.remove(room.id, room);
                    if (room.persisted) {
                        repository.cancelRoom(room.id, "房主离开", now);
                    }
                    return;
                }
                broadcastRoom(room);
                return;
            }
            if (STATUS_RUNNING.equals(room.status) && room.state != null) {
                room.state.actors.removeIf(actor -> !actor.bot
                        && user.userId().equals(actor.userId));
                room.state.players.remove(user.userId());
                if (humanActors(room.state) == 0) {
                    cancelLocked(room, now, "所有成员已离开");
                    return;
                }
                broadcastState(room);
            }
        }
    }

    /** 断线只清理等待中的休闲房间；进行中的对局交给比赛断线判负逻辑。 */
    public void onDisconnect(String userId) {
        for (Room room : List.copyOf(rooms.values())) {
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
                    rooms.remove(room.id, room);
                    if (room.persisted) {
                        repository.cancelRoom(
                                room.id, "成员断线", System.currentTimeMillis());
                    }
                } else {
                    if (userId.equals(room.ownerUserId)) {
                        room.ownerUserId = room.members.keySet().stream()
                                .findFirst().orElse(null);
                    }
                    broadcastRoom(room);
                }
            }
        }
    }

    public void input(GameIdentity user, String key, ExerciseInput input) {
        requireEnabled();
        Room room = requireRoom(key);
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
                broadcastState(room);
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
        if (now - lastSweepAt >= 2000) {
            lastSweepAt = now;
            sweep(now);
        }
        for (Room room : List.copyOf(rooms.values())) {
            if (rooms.get(room.id) != room) {
                continue;
            }
            synchronized (room.lock) {
                if (rooms.get(room.id) != room || !STATUS_RUNNING.equals(room.status)
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
                    finishLocked(room, now);
                    continue;
                }
                if (now - room.lastSnapshotAt >= properties.snapshotSeconds() * 1000L) {
                    persistState(room);
                }
                if (now - room.lastBroadcastAt >= properties.broadcastMillis()) {
                    broadcastState(room);
                }
            }
        }
    }

    public List<ResultRow> recentResults(int limit) {
        return repository.listRecentResults(clamp(limit, 1, 100));
    }

    private void startLocked(Room room, long now) {
        if (ExerciseEngine.MODE_TRAINING.equals(room.mode)) {
            for (int index = 1; index <= 3; index += 1) {
                Member bot = new Member(
                        "bot-" + room.id + "-" + index, "训练靶 " + index,
                        null, true, true, now);
                room.members.put(bot.userId, bot);
            }
        }
        List<PlayerSeed> seeds = new ArrayList<>();
        int index = 0;
        for (Member member : room.members.values()) {
            if (member.leftAt != 0) {
                continue;
            }
            if (ExerciseEngine.MODE_TEAM.equals(room.mode) && !member.bot) {
                member.team = index % 2 == 0 ? "A" : "B";
            }
            seeds.add(new PlayerSeed(
                    member.userId, member.nickname, member.team, member.bot));
            index += 1;
        }
        room.state = engine.create(
                room.id, room.mode, seeds, now, now + room.durationSeconds * 1000L,
                Integer.toUnsignedLong(room.id.hashCode()));
        room.status = STATUS_RUNNING;
        room.startedAt = now;
        room.lastBroadcastAt = 0;
        room.lastSnapshotAt = now;
        RoomRow row = row(room, writeState(room.state));
        if (room.persisted) {
            repository.updateRoomState(
                    room.id, row.stateJson(), room.state.seq, STATUS_RUNNING);
            for (Member member : room.members.values()) {
                if (member.bot && member.joinedAt == now) {
                    repository.insertMember(memberRow(room.id, member));
                }
            }
        } else {
            repository.insertRoom(row);
            room.persisted = true;
            for (Member member : room.members.values()) {
                repository.insertMember(memberRow(room.id, member));
            }
        }
        log.info("巡检房间开始 room={} mode={} players={}",
                room.id, room.mode, room.members.size());
        broadcastState(room);
        broadcastRoom(room);
    }

    private void finishLocked(Room room, long now) {
        if (!STATUS_RUNNING.equals(room.status) || room.state == null) {
            return;
        }
        room.status = STATUS_FINISHED;
        ExerciseState state = room.state;
        String winner = state.winnerUserId;
        String winnerTeam = state.winnerTeam;
        String reason = state.finishReason == null ? "巡检结束" : state.finishReason;
        rooms.remove(room.id, room);
        try {
            List<Map<String, Object>> standings = standings(state);
            if (room.persisted) {
                repository.finishRoom(
                        room.id, winner, winnerTeam, reason,
                        writeState(state), state.seq, now);
                for (ExercisePlayer actor : state.actors) {
                    if (actor.bot) {
                        continue;
                    }
                    repository.updateMemberStats(
                            room.id, actor.userId, actor.score,
                            actor.kills, actor.deaths, actor.assists);
                    repository.insertResult(new ResultRow(
                            UUID.randomUUID().toString(), room.id, room.mode,
                            actor.userId, actor.nickname, actor.team,
                            rankOf(state, actor), actor.score, actor.kills,
                            actor.deaths, actor.assists,
                            actor.userId.equals(winner), now));
                }
            }
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("type", "exercise_finished");
            frame.put("roomId", room.id);
            frame.put("roomCode", room.roomCode);
            frame.put("mode", room.mode);
            frame.put("matchId", room.matchId);
            frame.put("winnerUserId", winner);
            frame.put("winnerTeam", winnerTeam);
            frame.put("reason", reason);
            frame.put("standings", standings);
            frame.put("state", objectMapper.convertValue(state, Map.class));
            sendToRoom(room, frame);
            log.info("巡检房间结束 room={} winner={} team={} reason={}",
                    room.id, winner, winnerTeam, reason);
            if (SOURCE_TOURNAMENT.equals(room.source) && room.matchId != null
                    && winner != null) {
                publisher.publishEvent(new ArenaRoundFinishedEvent(
                        room.matchId, room.id, winner, reason));
            }
        } catch (Exception exception) {
            rooms.put(room.id, room);
            room.status = STATUS_RUNNING;
            log.error("巡检房间结算失败 room={}", room.id, exception);
        }
    }

    private void cancelLocked(Room room, long now, String reason) {
        rooms.remove(room.id, room);
        if (room.persisted) {
            repository.cancelRoom(room.id, reason, now);
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "exercise_finished");
        frame.put("roomId", room.id);
        frame.put("roomCode", room.roomCode);
        frame.put("mode", room.mode);
        frame.put("matchId", room.matchId);
        frame.put("winnerUserId", null);
        frame.put("reason", reason);
        frame.put("standings", List.of());
        sendToRoom(room, frame);
    }

    private void persistState(Room room) {
        if (!room.persisted || room.state == null) {
            return;
        }
        try {
            repository.updateRoomState(
                    room.id, writeState(room.state), room.state.seq, STATUS_RUNNING);
            room.lastSnapshotAt = System.currentTimeMillis();
        } catch (Exception exception) {
            log.warn("巡检快照写入失败 room={}", room.id, exception);
        }
    }

    private void broadcastState(Room room) {
        if (room.state == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (GameSession session : registry.all()) {
            boolean participant = room.members.containsKey(session.getUserId());
            boolean watching = room.matchId != null
                    && room.matchId.equals(session.getWatchingMatchId());
            if (!participant && !watching) {
                continue;
            }
            registry.send(session, stateFrame(room, session.getUserId(), now));
        }
        room.lastBroadcastAt = now;
    }

    private Map<String, Object> stateFrame(Room room, String viewerId, long now) {
        Map<String, Object> state = objectMapper.convertValue(room.state, Map.class);
        if (viewerId != null && room.members.containsKey(viewerId)) {
            state.put("actors", engine.visibleActors(room.state, viewerId, now));
            state.put("you", viewerId);
        } else {
            state.put("you", null);
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "exercise_state");
        frame.put("roomId", room.id);
        frame.put("roomCode", room.roomCode);
        frame.put("mode", room.mode);
        frame.put("matchId", room.matchId);
        frame.put("seq", room.state.seq);
        frame.put("serverTime", now);
        frame.put("you", viewerId);
        frame.put("state", state);
        return frame;
    }

    /** 房间帧也按接收者过滤状态，避免开局瞬间把全图位置发给参与者。 */
    private void broadcastRoom(Room room) {
        for (GameSession session : registry.all()) {
            boolean participant = room.members.containsKey(session.getUserId());
            boolean watching = room.matchId != null
                    && room.matchId.equals(session.getWatchingMatchId());
            if (!participant && !watching) {
                continue;
            }
            registry.send(session, Map.of(
                    "type", "exercise_room",
                    "room", roomView(room, session.getUserId())));
        }
    }

    private void sendToRoom(Room room, Object payload) {
        for (GameSession session : registry.all()) {
            boolean participant = room.members.containsKey(session.getUserId());
            boolean watching = room.matchId != null
                    && room.matchId.equals(session.getWatchingMatchId());
            if (participant || watching) {
                registry.send(session, payload);
            }
        }
    }

    private Map<String, Object> roomView(Room room, String viewerId) {
        List<Map<String, Object>> members = room.members.values().stream()
                .filter(member -> member.leftAt == 0)
                .map(member -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("userId", member.userId);
                    view.put("nickname", member.nickname);
                    view.put("team", member.team);
                    view.put("ready", member.ready);
                    view.put("bot", member.bot);
                    return view;
                })
                .toList();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("roomId", room.id);
        view.put("roomCode", room.roomCode);
        view.put("mode", room.mode);
        view.put("source", room.source);
        view.put("status", room.status);
        view.put("matchId", room.matchId);
        view.put("ownerUserId", room.ownerUserId);
        view.put("maxPlayers", room.maxPlayers);
        view.put("durationSeconds", room.durationSeconds);
        view.put("createdAt", room.createdAt);
        view.put("startedAt", room.startedAt == 0 ? null : room.startedAt);
        view.put("members", members);
        if (room.state != null) {
            Map<String, Object> state = objectMapper.convertValue(room.state, Map.class);
            if (viewerId != null && room.members.containsKey(viewerId)) {
                state.put("actors", engine.visibleActors(
                        room.state, viewerId, System.currentTimeMillis()));
            }
            view.put("state", state);
        }
        return view;
    }

    private static List<Map<String, Object>> standings(ExerciseState state) {
        List<ExercisePlayer> sorted = new ArrayList<>(state.actors);
        sorted.sort(Comparator
                .comparingInt((ExercisePlayer actor) -> actor.score).reversed()
                .thenComparing(Comparator.comparingInt(
                        (ExercisePlayer actor) -> actor.kills).reversed()));
        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = 1;
        for (ExercisePlayer actor : sorted) {
            if (actor.bot) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", rank);
            row.put("userId", actor.userId);
            row.put("nickname", actor.nickname);
            row.put("team", actor.team);
            row.put("score", actor.score);
            row.put("kills", actor.kills);
            row.put("deaths", actor.deaths);
            row.put("assists", actor.assists);
            row.put("winner", actor.userId.equals(state.winnerUserId));
            rows.add(row);
            rank += 1;
        }
        return rows;
    }

    private static int rankOf(ExerciseState state, ExercisePlayer actor) {
        List<ExercisePlayer> sorted = new ArrayList<>(state.actors);
        sorted.sort(Comparator
                .comparingInt((ExercisePlayer entry) -> entry.score).reversed()
                .thenComparing(Comparator.comparingInt(
                        (ExercisePlayer entry) -> entry.kills).reversed()));
        List<ExercisePlayer> humans = sorted.stream()
                .filter(entry -> !entry.bot)
                .toList();
        return humans.indexOf(actor) + 1;
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

    private Room requireRoom(String key) {
        if (key == null || key.isBlank()) {
            throw GameException.badRequest("GAME_FRAME_INVALID", "缺少房间 ID");
        }
        Room room = findRoom(key);
        if (room == null) {
            throw GameException.notFound("巡检房间不存在");
        }
        return room;
    }

    private Room findRoom(String key) {
        Room byId = rooms.get(key);
        if (byId != null) {
            return byId;
        }
        return rooms.values().stream()
                .filter(room -> key.equalsIgnoreCase(room.roomCode))
                .findFirst().orElse(null);
    }

    private String activeCasualRoomOf(String userId) {
        return rooms.values().stream()
                .filter(room -> SOURCE_CASUAL.equals(room.source))
                .filter(room -> STATUS_WAITING.equals(room.status)
                        || STATUS_RUNNING.equals(room.status))
                .filter(room -> room.members.containsKey(userId))
                .map(room -> room.id)
                .findFirst().orElse(null);
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
        for (Room room : List.copyOf(rooms.values())) {
            if (SOURCE_CASUAL.equals(room.source)
                    && STATUS_WAITING.equals(room.status)
                    && now - room.createdAt > WAITING_TTL_MILLIS) {
                synchronized (room.lock) {
                    if (rooms.remove(room.id, room) && room.persisted) {
                        repository.cancelRoom(room.id, "房间超时未开始", now);
                    }
                    sendToRoom(room, Map.of(
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
        if (!recovered.compareAndSet(false, true)) {
            return;
        }
        try {
            for (RoomRow row : repository.listRunningRooms()) {
                if (rooms.containsKey(row.id())) {
                    continue;
                }
                ExerciseState state = objectMapper.readValue(
                        row.stateJson(), ExerciseState.class);
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
                rooms.put(room.id, room);
                if (state.finished) {
                    synchronized (room.lock) {
                        finishLocked(room, System.currentTimeMillis());
                    }
                } else {
                    log.info("恢复巡检房间 room={} mode={} seq={}",
                            room.id, room.mode, state.seq);
                }
            }
        } catch (Exception exception) {
            recovered.set(false);
            log.warn("巡检房间恢复失败", exception);
        }
    }

    private String writeState(ExerciseState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception exception) {
            throw GameException.unavailable("巡检状态序列化失败");
        }
    }

    private static RoomRow row(Room room, String stateJson) {
        return new RoomRow(
                room.id, room.roomCode, room.mode, room.source, room.status,
                room.matchId, room.ownerUserId, room.maxPlayers, room.durationSeconds,
                stateJson, room.state == null ? 0 : room.state.seq,
                null, null, null, room.createdAt,
                room.startedAt == 0 ? null : room.startedAt, null);
    }

    private static MemberRow memberRow(String roomId, Member member) {
        return new MemberRow(
                roomId, member.userId, member.nickname, member.team,
                member.ready, member.bot, 0, 0, 0, 0,
                member.joinedAt, member.leftAt == 0 ? null : member.leftAt);
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

    private static final class Room {
        private final String id;
        private final String roomCode;
        private final String mode;
        private final String source;
        private final String matchId;
        private String ownerUserId;
        private final int maxPlayers;
        private final int durationSeconds;
        private final long createdAt;
        private final Object lock = new Object();
        private final Map<String, Member> members = new LinkedHashMap<>();
        private final Map<String, Deque<Long>> inputTimes = new LinkedHashMap<>();
        private ExerciseState state;
        private String status = STATUS_WAITING;
        private boolean persisted;
        private long startedAt;
        private long lastBroadcastAt;
        private long lastSnapshotAt;

        private Room(String id, String roomCode, String mode, String source,
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

    private static final class Member {
        private final String userId;
        private final String nickname;
        private String team;
        private boolean ready;
        private final boolean bot;
        private final long joinedAt;
        private long leftAt;

        private Member(String userId, String nickname, String team,
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
