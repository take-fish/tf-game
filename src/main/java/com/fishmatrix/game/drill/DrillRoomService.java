package com.fishmatrix.game.drill;

import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.TournamentPlayerRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 容量编排演练的锁步房间（进程内，单实例）。
 *
 * <p>两种模式：</p>
 * <ul>
 *   <li><b>休闲（CASUAL）</b>：有血量、会淘汰，25 轮收口，按存活/血量排名；</li>
 *   <li><b>锦标赛（TOURNAMENT）</b>：无血量、不淘汰，15 轮排位只记战绩（胜/负/平），
 *       局末按战绩排 1–8 名，并按名次发奖励（已合成装备 + 配额）。</li>
 * </ul>
 *
 * <p>棋盘与战斗由各端用同一 seed 的本地引擎推演（引擎是确定性 + mulberry32），
 * 服务端只做同局权威部分：下发 seed、同步轮次、仲裁选秀候选、收战绩并发奖。</p>
 */
@Slf4j
@Service
public class DrillRoomService {

    public static final String SOURCE_CASUAL = "CASUAL";
    public static final String SOURCE_TOURNAMENT = "TOURNAMENT";
    public static final String MODE_CASUAL = "CASUAL";
    public static final String MODE_TOURNAMENT = "TOURNAMENT";
    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_FINISHED = "FINISHED";
    /** 每轮开打前先进入 PREP 备战；截止时间到或全员点「直接开始」后进入 BATTLE。 */
    public static final String PHASE_PREP = "PREP";
    public static final String PHASE_BATTLE = "BATTLE";
    public static final String PHASE_FINISHED = "FINISHED";

    public static final int MAX_PLAYERS = 8;
    public static final int TOURNAMENT_ROUNDS = 15;
    public static final int CASUAL_ROUNDS = 25;
    /** 局间备战 25 秒；进局前 35 秒，由客户端按这两个值倒计时。 */
    public static final int INTERMISSION_SECONDS = 25;
    public static final int PRELUDE_SECONDS = 35;

    private static final int START_HP = 100;
    private static final long IDLE_TTL_MILLIS = 30 * 60 * 1000L;

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    /** 赛事 → 排位赛房间；房间本身也在进程内，重启后随房间一起失效。 */
    private final Map<String, String> tournamentRooms = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher publisher;

    /** 单元测试与本地脚本用：不接事件总线。 */
    public DrillRoomService() {
        this(event -> {
        });
    }

    @Autowired
    public DrillRoomService(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public static final class Player {
        public final String userId;
        public final String nickname;
        public final int seat;
        public int hp = START_HP;
        public boolean alive = true;
        public boolean ready;
        public int reportedRound;
        public int wins;
        public int losses;
        public int draws;

        Player(String userId, String nickname, int seat) {
            this.userId = userId;
            this.nickname = nickname;
            this.seat = seat;
        }

        int score() {
            return wins * 3 + draws;
        }
    }

    public static final class Room {
        public final String id;
        public final String source;
        public final String mode;
        /** 由锦标赛创建时非空；普通演练房间为空。 */
        public String tournamentId;
        public final int totalRounds;
        public final long seed;
        public final long createdAt = System.currentTimeMillis();
        public final Map<String, Player> players = new LinkedHashMap<>();
        public final Map<String, String> carouselWins = new ConcurrentHashMap<>();
        public final List<String> eliminationOrder = new ArrayList<>();
        /** 锦标赛最终名次（userId → 名次）与奖励。 */
        public final Map<String, Integer> placement = new LinkedHashMap<>();
        /** 备战窗口「直接开始」投票：全员都点才生效。 */
        public final Set<String> skipVotes = new LinkedHashSet<>();
        public boolean skipGranted;
        /** 临时聊天，最多保留最近 50 条。 */
        public final java.util.List<Map<String, Object>> chats = new java.util.ArrayList<>();
        public final Map<String, Map<String, Integer>> rewards = new LinkedHashMap<>();
        public String status = STATUS_WAITING;
        /** PREP 备战 / BATTLE 本轮进行中 / FINISHED 已结束。 */
        public String phase = PHASE_PREP;
        /** 本轮备战截止时间（epoch millis）；0 表示还没开局。 */
        public long prepEndsAt;
        public int round;
        public long updatedAt = createdAt;

        Room(String id, String source, String mode, int totalRounds, long seed) {
            this.id = id;
            this.source = source;
            this.mode = mode;
            this.totalRounds = totalRounds;
            this.seed = seed;
        }

        boolean tournament() {
            return MODE_TOURNAMENT.equals(mode);
        }
    }

    public Map<String, Object> create(GameIdentity identity, String source) {
        return create(identity, source, MODE_CASUAL);
    }

    public Map<String, Object> create(GameIdentity identity, String source, String mode) {
        String normalizedMode = MODE_TOURNAMENT.equalsIgnoreCase(mode == null ? "" : mode)
                ? MODE_TOURNAMENT
                : MODE_CASUAL;
        int rounds = MODE_TOURNAMENT.equals(normalizedMode) ? TOURNAMENT_ROUNDS : CASUAL_ROUNDS;
        String normalizedSource = MODE_TOURNAMENT.equals(normalizedMode)
                ? SOURCE_TOURNAMENT
                : normalizeSource(source);
        String roomId = UUID.randomUUID().toString().substring(0, 8);
        Room room = new Room(roomId, normalizedSource, normalizedMode, rounds,
                ThreadLocalRandom.current().nextLong());
        room.players.put(identity.userId(), new Player(identity.userId(), identity.nickname(), 0));
        rooms.put(roomId, room);
        return view(room);
    }

    /**
     * 为良跑跑锦标赛创建排位赛房间：参赛真人直接入座，机器人教练由各端本地补满 8 席。
     * 同一赛事只创建一个房间，重复调用返回现有房间。
     */
    public synchronized Map<String, Object> createForTournament(
            String tournamentId, List<TournamentPlayerRecord> players) {
        if (tournamentId == null || tournamentId.isBlank()) {
            throw GameException.badRequest("DRILL_TOURNAMENT_ID_REQUIRED", "缺少赛事 ID");
        }
        String existingRoomId = tournamentRooms.get(tournamentId);
        Room existing = existingRoomId == null ? null : rooms.get(existingRoomId);
        if (existing != null) {
            return view(existing);
        }
        if (players == null || players.size() < 2) {
            throw GameException.badRequest(
                    "DRILL_TOURNAMENT_NEEDS_TWO", "良跑跑锦标赛至少需要 2 名真人选手");
        }
        if (players.size() > MAX_PLAYERS) {
            throw GameException.badRequest(
                    "DRILL_TOURNAMENT_FULL", "良跑跑锦标赛最多 " + MAX_PLAYERS + " 名真人选手");
        }
        Room room = new Room(UUID.randomUUID().toString().substring(0, 8),
                SOURCE_TOURNAMENT, MODE_TOURNAMENT, TOURNAMENT_ROUNDS,
                ThreadLocalRandom.current().nextLong());
        room.tournamentId = tournamentId;
        for (TournamentPlayerRecord player : players) {
            room.players.put(player.userId(), new Player(
                    player.userId(),
                    player.nickname() == null || player.nickname().isBlank()
                            ? player.userId() : player.nickname(),
                    room.players.size()));
        }
        rooms.put(room.id, room);
        tournamentRooms.put(tournamentId, room.id);
        log.info("锦标赛 {} 排位赛房间 {} 已创建，{} 名真人", tournamentId, room.id, players.size());
        return view(room);
    }

    /** 按赛事查询排位赛房间；只有参赛真人可以进入。 */
    public Map<String, Object> tournamentRoom(String tournamentId, String userId) {
        String roomId = tournamentId == null ? null : tournamentRooms.get(tournamentId);
        Room room = roomId == null ? null : rooms.get(roomId);
        if (room == null) {
            throw GameException.notFound("排位赛房间不存在或已过期");
        }
        if (userId != null && !userId.isBlank() && !room.players.containsKey(userId)) {
            throw new GameException(HttpStatus.FORBIDDEN,
                    "DRILL_TOURNAMENT_NOT_MEMBER", "只有参赛选手可以进入排位赛");
        }
        return view(room);
    }

    /** 房间对应的赛事 ID；普通演练房间返回 null。 */
    public String tournamentIdOf(String roomId) {
        Room room = roomId == null ? null : rooms.get(roomId);
        return room == null ? null : room.tournamentId;
    }

    public Map<String, Object> join(GameIdentity identity, String roomId) {
        Room room = require(roomId);
        if (STATUS_FINISHED.equals(room.status)) {
            throw GameException.badRequest("DRILL_ROOM_CLOSED", "房间已结束");
        }
        Player existing = room.players.get(identity.userId());
        if (existing != null) {
            room.updatedAt = System.currentTimeMillis();
            return view(room);
        }
        // 锦标赛房间的座位在开赛时已按参赛名单锁定，不允许临时加入。
        if (room.tournamentId != null) {
            throw new GameException(HttpStatus.FORBIDDEN,
                    "DRILL_TOURNAMENT_NOT_MEMBER", "只有参赛选手可以进入排位赛");
        }
        if (room.players.size() >= MAX_PLAYERS) {
            throw GameException.badRequest("DRILL_ROOM_FULL", "房间已满（最多 8 人）");
        }
        if (STATUS_RUNNING.equals(room.status)) {
            throw GameException.badRequest("DRILL_ROOM_RUNNING", "房间已开始，无法加入");
        }
        room.players.put(identity.userId(),
                new Player(identity.userId(), identity.nickname(), room.players.size()));
        room.updatedAt = System.currentTimeMillis();
        return view(room);
    }

    public Map<String, Object> ready(GameIdentity identity, String roomId) {
        Room room = require(roomId);
        Player player = requirePlayer(room, identity.userId());
        if (STATUS_RUNNING.equals(room.status)) {
            return view(room);
        }
        player.ready = true;
        room.updatedAt = System.currentTimeMillis();
        if (room.players.size() >= 2 && room.players.values().stream().allMatch(member -> member.ready)) {
            room.status = STATUS_RUNNING;
            room.round = 1;
            enterPrep(room);
        }
        return view(room);
    }

    /** 休闲模式轮末报到：提交血量，血量归零即淘汰。 */
    public Map<String, Object> report(
            GameIdentity identity, String roomId, int round, int hp, String carouselId) {
        return report(identity, roomId, round, hp, null, carouselId);
    }

    /**
     * 统一轮末报到：锦标赛房间按 {@code result}（win/loss/draw）记战绩，
     * 休闲房间按 {@code hp} 结算血量；由房间模式自动分流。
     */
    public Map<String, Object> report(
            GameIdentity identity, String roomId, int round, int hp, String result, String carouselId) {
        Room room = require(roomId);
        if (room.tournament()) {
            return reportTournament(identity, roomId, round, result, carouselId);
        }
        Player player = requirePlayer(room, identity.userId());
        requireReportable(room, round);
        if (player.reportedRound >= round) {
            return view(room);
        }
        player.reportedRound = round;
        player.hp = Math.max(0, hp);
        if (hp <= 0 && player.alive) {
            player.alive = false;
            room.eliminationOrder.add(player.userId);
        }
        recordCarousel(room, player.userId, carouselId);
        room.updatedAt = System.currentTimeMillis();
        advanceIfAllReported(room);
        return view(room);
    }

    /**
     * 锦标赛模式轮末报到：只记战绩，不结算血量、不淘汰。
     *
     * @param result 本轮对位结果：win / loss / draw
     */
    public Map<String, Object> reportTournament(
            GameIdentity identity, String roomId, int round, String result, String carouselId) {
        Room room = require(roomId);
        Player player = requirePlayer(room, identity.userId());
        if (!room.tournament()) {
            throw GameException.badRequest("DRILL_ROOM_NOT_TOURNAMENT", "当前不是锦标赛房间");
        }
        requireReportable(room, round);
        if (player.reportedRound >= round) {
            return view(room);
        }
        player.reportedRound = round;
        String normalized = result == null ? "" : result.trim().toUpperCase();
        switch (normalized) {
            case "WIN" -> player.wins += 1;
            case "DRAW" -> player.draws += 1;
            default -> player.losses += 1;
        }
        recordCarousel(room, player.userId, carouselId);
        room.updatedAt = System.currentTimeMillis();
        advanceIfAllReported(room);
        return view(room);
    }

    /**
     * 备战窗口「直接开始」：一方点击只记录，**必须双方都点击才生效**；
     * 未全员点击时 skipGranted 保持 false，客户端可展示「等待对方确认」。
     */
    public Map<String, Object> requestSkip(GameIdentity identity, String roomId) {
        Room room = require(roomId);
        requirePlayer(room, identity.userId());
        if (STATUS_FINISHED.equals(room.status)) {
            throw GameException.badRequest("DRILL_ROOM_CLOSED", "房间已结束");
        }
        syncPhase(room);
        if (!room.skipGranted) {
            room.skipVotes.add(identity.userId());
            if (!room.players.isEmpty() && room.skipVotes.size() >= room.players.size()) {
                // 全员都点了「直接开始」：跳过剩余备战，本轮立即开打。
                room.skipGranted = true;
                room.phase = PHASE_BATTLE;
                room.prepEndsAt = System.currentTimeMillis();
            }
        }
        room.updatedAt = System.currentTimeMillis();
        return view(room);
    }

    /** 房间内临时聊天：固定小窗，只保留最近 50 条。 */
    public Map<String, Object> chat(GameIdentity identity, String roomId, String text) {
        Room room = require(roomId);
        Player player = requirePlayer(room, identity.userId());
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) {
            return view(room);
        }
        if (clean.length() > 200) {
            clean = clean.substring(0, 200);
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("userId", player.userId);
        message.put("nickname", player.nickname);
        message.put("text", clean);
        message.put("at", System.currentTimeMillis());
        room.chats.add(message);
        while (room.chats.size() > 50) {
            room.chats.remove(0);
        }
        room.updatedAt = System.currentTimeMillis();
        return view(room);
    }

    /** 赛事播报：以系统身份写入房间频道，不占用玩家发言。 */
    public void systemSay(String roomId, String text) {
        Room room = rooms.get(roomId);
        if (room == null) {
            return;
        }
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) {
            return;
        }
        if (clean.length() > 200) {
            clean = clean.substring(0, 200);
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("userId", "system");
        message.put("nickname", "赛事播报");
        message.put("text", clean);
        message.put("at", System.currentTimeMillis());
        message.put("kind", "system");
        room.chats.add(message);
        while (room.chats.size() > 50) {
            room.chats.remove(0);
        }
        room.updatedAt = System.currentTimeMillis();
    }

    public Map<String, Object> state(GameIdentity identity, String roomId) {
        return view(require(roomId));
    }

    /** 排位赛终局排名，供淘汰赛播种。 */
    public record RankedPlayer(
            String userId, String nickname, int score, int wins, int losses, int draws) {
    }

    public List<RankedPlayer> ranking(String roomId) {
        Room room = require(roomId);
        if (!room.tournament()) {
            throw GameException.badRequest("DRILL_NOT_TOURNAMENT", "不是锦标赛房间");
        }
        if (!STATUS_FINISHED.equals(room.status)) {
            throw GameException.badRequest("DRILL_TOURNAMENT_RUNNING", "排位赛尚未结束");
        }
        List<Player> ordered = new ArrayList<>(room.players.values());
        ordered.sort(Comparator
                .comparingInt(Player::score).reversed()
                .thenComparing(Comparator.comparingInt((Player member) -> member.wins).reversed())
                .thenComparingInt(member -> member.losses));
        List<RankedPlayer> list = new ArrayList<>();
        for (Player member : ordered) {
            list.add(new RankedPlayer(member.userId, member.nickname, member.score(),
                    member.wins, member.losses, member.draws));
        }
        return list;
    }

    public boolean isMember(String roomId, String userId) {
        Room room = rooms.get(roomId);
        return room != null && room.players.containsKey(userId);
    }

    public void sweepIdle() {
        long now = System.currentTimeMillis();
        rooms.entrySet().removeIf(entry -> now - entry.getValue().updatedAt > IDLE_TTL_MILLIS);
        // 房间被回收后，赛事到房间的映射也要清掉，避免一直指向已失效的房间。
        tournamentRooms.entrySet().removeIf(entry -> !rooms.containsKey(entry.getValue()));
    }

    private void advanceIfAllReported(Room room) {
        boolean allReported = room.players.values().stream()
                .allMatch(member -> !member.alive || member.reportedRound >= room.round);
        if (!allReported) {
            return;
        }
        if (room.tournament()) {
            if (room.round >= room.totalRounds) {
                finishTournament(room);
            } else {
                room.round += 1;
                enterPrep(room);
            }
            return;
        }
        long aliveCount = room.players.values().stream().filter(member -> member.alive).count();
        if (aliveCount <= 1) {
            finishCasual(room);
        } else {
            room.round += 1;
            enterPrep(room);
        }
    }

    /** 进入一轮备战：清空上一轮的「直接开始」投票，并按进局前/局间设置截止时间。 */
    private void enterPrep(Room room) {
        room.phase = PHASE_PREP;
        room.skipVotes.clear();
        room.skipGranted = false;
        int seconds = room.round <= 1 ? PRELUDE_SECONDS : INTERMISSION_SECONDS;
        room.prepEndsAt = System.currentTimeMillis() + seconds * 1000L;
    }

    /** 截止时间到 → BATTLE（客户端轮询 state 时由服务端翻阶段）。 */
    private void syncPhase(Room room) {
        if (STATUS_RUNNING.equals(room.status)
                && PHASE_PREP.equals(room.phase)
                && room.prepEndsAt > 0
                && System.currentTimeMillis() >= room.prepEndsAt) {
            room.phase = PHASE_BATTLE;
        }
    }

    private void finishCasual(Room room) {
        room.status = STATUS_FINISHED;
        room.phase = PHASE_FINISHED;
        room.prepEndsAt = 0;
        List<Player> ranked = new ArrayList<>(room.players.values());
        ranked.sort(Comparator
                .comparing((Player member) -> !member.alive)
                .thenComparing(member -> member.alive ? 0 : room.eliminationOrder.indexOf(member.userId))
                .thenComparing(member -> -member.hp));
        room.eliminationOrder.clear();
        int rank = 1;
        for (Player member : ranked) {
            room.eliminationOrder.add(member.userId);
            room.placement.put(member.userId, rank);
            rank += 1;
        }
    }

    private void finishTournament(Room room) {
        room.status = STATUS_FINISHED;
        room.phase = PHASE_FINISHED;
        room.prepEndsAt = 0;
        List<Player> ranked = new ArrayList<>(room.players.values());
        ranked.sort(Comparator
                .comparingInt(Player::score).reversed()
                .thenComparing(Comparator.comparingInt((Player member) -> member.wins).reversed())
                .thenComparingInt(member -> member.losses));
        int rank = 1;
        for (Player member : ranked) {
            room.placement.put(member.userId, rank);
            room.rewards.put(member.userId, rewardOf(rank));
            rank += 1;
        }
        if (room.tournamentId != null) {
            try {
                publisher.publishEvent(
                        new DrillTournamentEvents.PlacementFinished(room.tournamentId, room.id));
            } catch (Exception exception) {
                log.warn("排位赛结束事件处理失败 tournament={} room={}",
                        room.tournamentId, room.id, exception);
            }
        }
    }

    /** 排位奖励：1 名 4 件/30 配额，2 名 3/25，3–4 名 2/20，其余 1/15。 */
    public static Map<String, Integer> rewardOf(int placement) {
        int items = placement == 1 ? 4 : placement == 2 ? 3 : placement <= 4 ? 2 : 1;
        int quota = placement == 1 ? 30 : placement == 2 ? 25 : placement <= 4 ? 20 : 15;
        return Map.of("items", items, "quota", quota);
    }

    private void recordCarousel(Room room, String userId, String carouselId) {
        if (carouselId != null && !carouselId.isBlank()) {
            room.carouselWins.putIfAbsent(carouselId, userId);
        }
    }

    private void requireReportable(Room room, int round) {
        syncPhase(room);
        if (!STATUS_RUNNING.equals(room.status)) {
            throw GameException.badRequest("DRILL_ROOM_NOT_RUNNING", "房间未开始");
        }
        if (!PHASE_BATTLE.equals(room.phase)) {
            throw GameException.badRequest("DRILL_ROOM_PREP", "备战尚未结束");
        }
        if (round != room.round) {
            throw GameException.badRequest("DRILL_ROOM_ROUND_MISMATCH", "轮次不一致，请等待同步");
        }
    }

    private Map<String, Object> view(Room room) {
        syncPhase(room);
        List<Player> ordered = new ArrayList<>(room.players.values());
        if (room.tournament()) {
            ordered.sort(Comparator
                    .comparingInt(Player::score).reversed()
                    .thenComparing(Comparator.comparingInt((Player member) -> member.wins).reversed())
                    .thenComparingInt(member -> member.losses));
        }
        List<Map<String, Object>> players = new ArrayList<>();
        for (Player member : ordered) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", member.userId);
            row.put("nickname", member.nickname);
            row.put("seat", member.seat);
            row.put("ready", member.ready);
            row.put("wins", member.wins);
            row.put("losses", member.losses);
            row.put("draws", member.draws);
            row.put("score", member.score());
            row.put("reportedRound", member.reportedRound);
            if (!room.tournament()) {
                row.put("hp", member.hp);
                row.put("alive", member.alive);
            }
            row.put("placement", room.placement.get(member.userId));
            row.put("reward", room.rewards.get(member.userId));
            players.add(row);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", room.id);
        view.put("source", room.source);
        view.put("mode", room.mode);
        view.put("tournamentId", room.tournamentId);
        view.put("status", room.status);
        view.put("phase", room.phase);
        view.put("seed", room.seed);
        view.put("round", room.round);
        view.put("totalRounds", room.totalRounds);
        view.put("serverTime", System.currentTimeMillis());
        view.put("prepEndsAt", room.prepEndsAt);
        view.put("maxPlayers", MAX_PLAYERS);
        view.put("intermissionSeconds", INTERMISSION_SECONDS);
        view.put("preludeSeconds", PRELUDE_SECONDS);
        view.put("players", players);
        view.put("carouselWins", Map.copyOf(room.carouselWins));
        view.put("placement", List.copyOf(room.eliminationOrder));
        view.put("placements", Map.copyOf(room.placement));
        view.put("skipVotes", List.copyOf(room.skipVotes));
        view.put("skipGranted", room.skipGranted);
        view.put("chats", List.copyOf(room.chats));
        view.put("rewards", Map.copyOf(room.rewards));
        return view;
    }

    private Room require(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            throw GameException.badRequest("DRILL_ROOM_ID_REQUIRED", "缺少房间 ID");
        }
        Room room = rooms.get(roomId);
        if (room == null) {
            throw GameException.notFound("演练房间不存在或已过期");
        }
        return room;
    }

    private static Player requirePlayer(Room room, String userId) {
        Player player = room.players.get(userId);
        if (player == null) {
            throw GameException.badRequest("DRILL_ROOM_NOT_MEMBER", "不在该房间中");
        }
        return player;
    }

    private static String normalizeSource(String source) {
        return SOURCE_TOURNAMENT.equalsIgnoreCase(source == null ? "" : source)
                ? SOURCE_TOURNAMENT
                : SOURCE_CASUAL;
    }
}
