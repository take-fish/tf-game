package com.fishmatrix.game.arena;

import com.fishmatrix.game.config.ExerciseProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 集群巡检对抗演练：2D 俯视角、服务端权威的轻量 FPS。
 *
 * <p>地图固定 1280×720，四个出生点、五处资源/控制点与硬/软掩体。
 * 命中 +1、击杀 +5、助攻 +2、拾取 +2、独占控制点每 3 秒 +1。
 * 只按最终积分判定输赢；对局内分数不写入冠军榜。</p>
 *
 * <p>引擎不访问数据库与网络，只修改传入状态，便于按固定输入序列复现。
 * 地图见 {@link ExerciseArenaMap}，碰撞见 {@link ExerciseGeometry}，
 * 移动/开火/技能结算见 {@link ExerciseCombat}。</p>
 */
@RequiredArgsConstructor
@Component
public class ExerciseEngine {

    public static final String MODE_FFA = "FFA";
    public static final String MODE_TEAM = "TEAM";
    public static final String MODE_TRAINING = "TRAINING";

    public static final double WIDTH = ExerciseGeometry.WIDTH;
    public static final double HEIGHT = ExerciseGeometry.HEIGHT;

    private final ExerciseProperties properties;
    private final ExerciseCombat combat = new ExerciseCombat();

    public ExerciseState create(
            String roomId, String mode, List<PlayerSeed> seeds,
            long startedAt, long endsAt, long seed) {
        ExerciseState state = new ExerciseState();
        state.gameId = ArenaGames.CLUSTER_PATROL;
        state.roomId = roomId;
        state.mode = mode;
        state.status = "RUNNING";
        state.startedAt = startedAt;
        state.endsAt = endsAt;
        state.serverTime = startedAt;
        state.seed = seed;
        state.lastTickAt = startedAt;
        state.seedValue = seed;
        ExerciseArenaMap.build(state, startedAt);

        List<PlayerSeed> entrants = new ArrayList<>(seeds);
        for (int index = 0; index < entrants.size(); index += 1) {
            PlayerSeed entry = entrants.get(index);
            ExercisePlayer actor = new ExercisePlayer();
            actor.userId = entry.userId();
            actor.nickname = entry.nickname();
            actor.team = entry.team();
            actor.bot = entry.bot();
            int spawnIndex = ExerciseArenaMap.spawnIndexFor(state, entry, index);
            double[] spawn = ExerciseGeometry.SPAWNS.get(spawnIndex);
            actor.x = spawn[0];
            actor.y = spawn[1];
            actor.aim = Math.atan2(HEIGHT / 2 - actor.y, WIDTH / 2 - actor.x);
            actor.hp = 100;
            actor.alive = true;
            state.actors.add(actor);
            if (!actor.bot) {
                state.players.add(actor.userId);
            }
        }
        state.addEvent(startedAt, "START", null,
                MODE_TEAM.equals(mode) ? "团队巡检开始" : "巡检演练开始");
        return state;
    }

    /** 客户端输入只更新意图；移动、开火与技能结算都在 tick 中完成。 */
    public void input(ExerciseState state, String userId, ExerciseInput input, long now) {
        combat.input(state, userId, input, now);
    }

    public void tick(ExerciseState state, long now) {
        combat.tick(state, now);
    }

    /** 服务端视野过滤：自身始终可见，其余目标需在感知范围内且无硬掩体遮挡。 */
    public List<ExercisePlayer> visibleActors(ExerciseState state, String viewerUserId, long now) {
        ExercisePlayer viewer = ExerciseCombat.actor(state, viewerUserId);
        if (viewer == null) {
            return List.copyOf(state.actors);
        }
        boolean scan = viewer.scanUntil > now;
        List<ExercisePlayer> visible = new ArrayList<>();
        for (ExercisePlayer candidate : state.actors) {
            if (candidate.userId.equals(viewerUserId) || scan) {
                visible.add(candidate);
                continue;
            }
            double distance = ExerciseGeometry.distance(
                    viewer.x, viewer.y, candidate.x, candidate.y);
            if (distance <= properties.perceptionRange()
                    && !ExerciseGeometry.blocked(
                            state, viewer.x, viewer.y, candidate.x, candidate.y, now, false)) {
                visible.add(candidate);
            }
        }
        return visible;
    }

    public record PlayerSeed(String userId, String nickname, String team, boolean bot) {
    }

    public static class ExerciseState extends ArenaState {
        public String roomId;
        public String mode;
        public String status;
        public String winnerTeam;
        public long lastTickAt;
        public long seedValue;
        public double width = WIDTH;
        public double height = HEIGHT;
        public List<ExercisePlayer> actors = new ArrayList<>();
        public List<Obstacle> obstacles = new ArrayList<>();
        public List<ControlPoint> points = new ArrayList<>();
        public List<Tracer> tracers = new ArrayList<>();
    }

    public static class ExercisePlayer {
        public String userId;
        public String nickname;
        public String team;
        public boolean bot;
        public double x;
        public double y;
        public double aim;
        public int hp = 100;
        public boolean alive = true;
        public long respawnAt;
        public int score;
        public int kills;
        public int deaths;
        public int assists;
        public int shots;
        public int hits;
        public long lastFireAt;
        public long lastInputAt;
        public double inputX;
        public double inputY;
        public long scanUntil;
        public long shieldUntil;
        public long speedUntil;
        public Map<String, Long> cooldowns = new LinkedHashMap<>();
        public Map<String, Long> damageAt = new LinkedHashMap<>();
    }

    public static class Obstacle {
        public double x;
        public double y;
        public double w;
        public double h;
        public boolean hard;
        public long expiresAt;
    }

    public static class ControlPoint {
        public String id;
        public double x;
        public double y;
        public String ownerUserId;
        public String ownerTeam;
        public String holderUserId;
        public long holdSince;
        public long lastIncomeAt;
        public long resourceAt;
    }

    public static class Tracer {
        public String userId;
        public double x1;
        public double y1;
        public double x2;
        public double y2;
        public boolean hit;
        public long at;
    }
}
