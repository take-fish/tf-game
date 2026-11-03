package com.fishmatrix.game.drill;

import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

/**
 * 容量编排演练的锁步房间（进程内，单实例）。
 *
 * <p>良跑跑是回合制多阶段玩法，一局最多 8 人。这里只做“同局”的权威部分：
 * 下发同一个 seed、同步轮次、仲裁选秀轮盘的共享候选、收集轮末血量并结算淘汰与名次。
 * 棋盘与战斗由各端用同一 seed 的本地引擎推演（引擎本身是确定性 + mulberry32），
 * 因此不需要服务端跑一遍战斗。</p>
 *
 * <p>阶段约定：{@code WAITING} 等人 → {@code RUNNING} 逐轮推进 → {@code FINISHED} 出名次。
 * 每轮由客户端在本地准备期结束后提交 {@code report}（血量 / 是否存活 / 本轮选秀抢到的候选）。</p>
 */
@Service
public class DrillRoomService {

    public static final String SOURCE_CASUAL = "CASUAL";
    public static final String SOURCE_TOURNAMENT = "TOURNAMENT";
    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_FINISHED = "FINISHED";

    private static final int MAX_PLAYERS = 8;
    private static final int START_HP = 100;
    /** 房间闲置多久自动清理，避免内存泄漏。 */
    private static final long IDLE_TTL_MILLIS = 30 * 60 * 1000L;

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    public static final class Player {
        public final String userId;
        public final String nickname;
        public final int seat;
        public int hp = START_HP;
        public boolean alive = true;
        public boolean ready;
        /** 已提交报到的最靠前的轮次，避免同一轮重复提交。 */
        public int reportedRound;

        Player(String userId, String nickname, int seat) {
            this.userId = userId;
            this.nickname = nickname;
            this.seat = seat;
        }
    }

    public static final class Room {
        public final String id;
        public final String source;
        public final long seed;
        public final long createdAt = System.currentTimeMillis();
        public final Map<String, Player> players = new LinkedHashMap<>();
        /** 选秀轮盘：itemId -> 抢到的 userId，先到先得。 */
        public final Map<String, String> carouselWins = new ConcurrentHashMap<>();
        /** 淘汰顺序，先淘汰的排前面。 */
        public final List<String> eliminationOrder = new ArrayList<>();
        public String status = STATUS_WAITING;
        public int round;
        public long updatedAt = createdAt;

        Room(String id, String source, long seed) {
            this.id = id;
            this.source = source;
            this.seed = seed;
        }
    }

    public Map<String, Object> create(GameIdentity identity, String source) {
        String roomId = UUID.randomUUID().toString().substring(0, 8);
        Room room = new Room(roomId, normalizeSource(source), ThreadLocalRandom.current().nextLong());
        room.players.put(identity.userId(), new Player(identity.userId(), identity.nickname(), 0));
        rooms.put(roomId, room);
        return view(room);
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
        if (room.players.size() >= MAX_PLAYERS) {
            throw GameException.badRequest("DRILL_ROOM_FULL", "房间已满（最多 8 人）");
        }
        if (STATUS_RUNNING.equals(room.status)) {
            throw GameException.badRequest("DRILL_ROOM_RUNNING", "房间已开始，无法加入");
        }
        room.players.put(identity.userId(), new Player(identity.userId(), identity.nickname(), room.players.size()));
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
        }
        return view(room);
    }

    /**
     * 轮末报到：客户端在本地跑完一轮后提交。
     *
     * @param round      客户端跑完的轮次，必须等于房间当前轮次
     * @param hp         剩余血量（≤0 视为淘汰）
     * @param carouselId 本轮选秀抢到的候选（没有则 null）
     */
    public Map<String, Object> report(
            GameIdentity identity, String roomId, int round, int hp, String carouselId) {
        Room room = require(roomId);
        Player player = requirePlayer(room, identity.userId());
        if (!STATUS_RUNNING.equals(room.status)) {
            throw GameException.badRequest("DRILL_ROOM_NOT_RUNNING", "房间未开始");
        }
        if (round != room.round) {
            throw GameException.badRequest("DRILL_ROOM_ROUND_MISMATCH", "轮次不一致，请等待同步");
        }
        if (player.reportedRound >= round) {
            return view(room);
        }
        player.reportedRound = round;
        player.hp = Math.max(0, hp);
        if (hp <= 0 && player.alive) {
            player.alive = false;
            room.eliminationOrder.add(player.userId);
        }
        if (carouselId != null && !carouselId.isBlank()) {
            room.carouselWins.putIfAbsent(carouselId, player.userId);
        }
        room.updatedAt = System.currentTimeMillis();

        boolean allReported = room.players.values().stream()
                .allMatch(member -> !member.alive || member.reportedRound >= round);
        if (allReported) {
            long aliveCount = room.players.values().stream().filter(member -> member.alive).count();
            if (aliveCount <= 1) {
                finish(room);
            } else {
                room.round += 1;
            }
        }
        return view(room);
    }

    public Map<String, Object> state(GameIdentity identity, String roomId) {
        return view(require(roomId));
    }

    public void sweepIdle() {
        long now = System.currentTimeMillis();
        rooms.entrySet().removeIf(entry -> now - entry.getValue().updatedAt > IDLE_TTL_MILLIS);
    }

    private void finish(Room room) {
        room.status = STATUS_FINISHED;
        List<Player> ranked = new ArrayList<>(room.players.values());
        ranked.sort(Comparator
                .comparing((Player member) -> !member.alive)
                .thenComparing(member -> member.alive ? 0 : room.eliminationOrder.indexOf(member.userId))
                .thenComparing(member -> -member.hp));
        room.eliminationOrder.clear();
        for (Player member : ranked) {
            room.eliminationOrder.add(member.userId);
        }
    }

    private Map<String, Object> view(Room room) {
        List<Map<String, Object>> players = new ArrayList<>();
        for (Player member : room.players.values()) {
            players.add(Map.of(
                    "userId", member.userId,
                    "nickname", member.nickname,
                    "seat", member.seat,
                    "hp", member.hp,
                    "alive", member.alive,
                    "ready", member.ready));
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", room.id);
        view.put("source", room.source);
        view.put("status", room.status);
        view.put("seed", room.seed);
        view.put("round", room.round);
        view.put("maxPlayers", MAX_PLAYERS);
        view.put("players", players);
        view.put("carouselWins", Map.copyOf(room.carouselWins));
        view.put("placement", List.copyOf(room.eliminationOrder));
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
