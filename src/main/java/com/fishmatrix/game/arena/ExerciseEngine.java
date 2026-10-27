package com.fishmatrix.game.arena;

import com.fishmatrix.game.config.ExerciseProperties;
import com.fishmatrix.game.model.GameException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 集群巡检对抗演练：2D 俯视角、服务端权威的轻量 FPS。
 *
 * <p>地图固定 1280×720，四个出生点、五处资源/控制点与硬/软掩体。
 * 命中 +1、击杀 +5、助攻 +2、拾取 +2、独占控制点每 3 秒 +1。
 * 只按最终积分判定输赢；对局内分数不写入冠军榜。</p>
 *
 * <p>引擎不访问数据库与网络，只修改传入状态，便于按固定输入序列复现。</p>
 */
@Component
public class ExerciseEngine {

    public static final String MODE_FFA = "FFA";
    public static final String MODE_TEAM = "TEAM";
    public static final String MODE_TRAINING = "TRAINING";

    public static final double WIDTH = 1280;
    public static final double HEIGHT = 720;
    private static final double MARGIN = 26;
    private static final double PLAYER_RADIUS = 14;
    private static final double SPEED = 220;
    private static final double SPRINT_SPEED = 340;
    private static final double SOFT_SPEED = 130;
    private static final long RESPAWN_MILLIS = 5000;
    private static final long BOT_RESPAWN_MILLIS = 2500;
    private static final long FIRE_COOLDOWN = 250;
    private static final double FIRE_RANGE = 540;
    private static final double HIT_RADIUS = 20;
    private static final double CONE_COS = Math.cos(Math.toRadians(6));
    private static final int HIT_DAMAGE = 34;
    private static final long SCAN_MILLIS = 2000;
    private static final long SHIELD_MILLIS = 3000;
    private static final long PATROL_MILLIS = 3000;
    private static final long BARRIER_MILLIS = 6000;
    private static final long PICKUP_INTERVAL = 12_000;
    private static final double PICKUP_RADIUS = 34;
    private static final double CAPTURE_RADIUS = 66;
    private static final long CAPTURE_INTERVAL = 3000;
    private static final long ASSIST_WINDOW = 5000;
    private static final int MAX_TEMPORARY_OBSTACLES = 16;

    private final ExerciseProperties properties;

    public ExerciseEngine(ExerciseProperties properties) {
        this.properties = properties;
    }

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
        buildMap(state, startedAt);

        List<PlayerSeed> entrants = new ArrayList<>(seeds);
        for (int index = 0; index < entrants.size(); index += 1) {
            PlayerSeed entry = entrants.get(index);
            ExercisePlayer actor = new ExercisePlayer();
            actor.userId = entry.userId();
            actor.nickname = entry.nickname();
            actor.team = entry.team();
            actor.bot = entry.bot();
            int spawnIndex = spawnIndexFor(state, entry, index);
            double[] spawn = SPAWNS.get(spawnIndex);
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

    private static int spawnIndexFor(ExerciseState state, PlayerSeed entry, int index) {
        if (MODE_TRAINING.equals(state.mode) && entry.bot()) {
            return 1 + (index % 3);
        }
        if (MODE_TEAM.equals(state.mode) && "B".equals(entry.team())) {
            return index % 2 == 0 ? 1 : 3;
        }
        if (MODE_TEAM.equals(state.mode)) {
            return index % 2 == 0 ? 0 : 2;
        }
        return index % 4;
    }

    private static void buildMap(ExerciseState state, long startedAt) {
        addHard(state, 250, 160, 170, 36);
        addHard(state, 860, 160, 170, 36);
        addHard(state, 250, 524, 170, 36);
        addHard(state, 860, 524, 170, 36);
        addHard(state, 610, 300, 60, 24);
        addHard(state, 610, 396, 60, 24);
        addHard(state, 450, 250, 36, 90);
        addHard(state, 794, 380, 36, 90);

        addSoft(state, 470, 430, 28, 80);
        addSoft(state, 782, 210, 28, 80);
        addSoft(state, 560, 170, 24, 70);
        addSoft(state, 696, 480, 24, 70);

        List<double[]> points = List.of(
                new double[] {640, 140},
                new double[] {640, 580},
                new double[] {330, 360},
                new double[] {950, 360},
                new double[] {640, 360});
        for (int index = 0; index < points.size(); index += 1) {
            double[] point = points.get(index);
            ControlPoint control = new ControlPoint();
            control.id = "cp-" + (index + 1);
            control.x = point[0];
            control.y = point[1];
            control.resourceAt = startedAt + 3000;
            control.lastIncomeAt = startedAt;
            state.points.add(control);
        }
    }

    private static void addHard(ExerciseState state, double x, double y, double w, double h) {
        Obstacle obstacle = new Obstacle();
        obstacle.x = x;
        obstacle.y = y;
        obstacle.w = w;
        obstacle.h = h;
        obstacle.hard = true;
        state.obstacles.add(obstacle);
    }

    private static void addSoft(ExerciseState state, double x, double y, double w, double h) {
        Obstacle obstacle = new Obstacle();
        obstacle.x = x;
        obstacle.y = y;
        obstacle.w = w;
        obstacle.h = h;
        obstacle.hard = false;
        state.obstacles.add(obstacle);
    }

    /** 客户端输入只更新意图；移动、开火与技能结算都在 tick 中完成。 */
    public void input(ExerciseState state, String userId, ExerciseInput input, long now) {
        state.serverTime = now;
        if (state.finished) {
            throw GameException.conflict("GAME_ARENA_FINISHED", "本局已经结束");
        }
        ExercisePlayer actor = actor(state, userId);
        if (actor == null) {
            throw GameException.forbidden("只有房间成员可以操作");
        }
        if (actor.bot) {
            throw GameException.forbidden("训练靶不接受操作");
        }
        if (!actor.alive) {
            return;
        }
        double moveX = clamp(input.moveX() == null ? 0 : input.moveX(), -1, 1);
        double moveY = clamp(input.moveY() == null ? 0 : input.moveY(), -1, 1);
        double length = Math.hypot(moveX, moveY);
        if (length > 1) {
            moveX /= length;
            moveY /= length;
        }
        actor.inputX = moveX;
        actor.inputY = moveY;
        actor.lastInputAt = now;
        if (input.aim() != null) {
            actor.aim = normalizeAngle(input.aim());
        }
        if (input.skill() != null && !input.skill().isBlank()) {
            useSkill(state, actor, input, now);
        }
        if (Boolean.TRUE.equals(input.fire())) {
            fire(state, actor, now);
        }
        state.seq += 1;
    }

    private void useSkill(ExerciseState state, ExercisePlayer actor,
                          ExerciseInput input, long now) {
        String skill = input.skill().strip().toLowerCase();
        String key;
        long cooldown;
        switch (skill) {
            case "scan", "pulse" -> {
                key = "scan";
                cooldown = 15_000;
                requireReady(actor, key, now);
                actor.cooldowns.put(key, now + cooldown);
                actor.scanUntil = now + SCAN_MILLIS;
                state.addEvent(now, "SKILL", actor.userId, "脉冲扫描：全图感知 2 秒");
            }
            case "shield" -> {
                key = "shield";
                cooldown = 18_000;
                requireReady(actor, key, now);
                actor.cooldowns.put(key, now + cooldown);
                actor.shieldUntil = now + SHIELD_MILLIS;
                state.addEvent(now, "SKILL", actor.userId, "隔离护盾：3 秒免疫伤害");
            }
            case "barrier" -> {
                key = "barrier";
                cooldown = 20_000;
                requireReady(actor, key, now);
                actor.cooldowns.put(key, now + cooldown);
                placeBarrier(state, actor, input, now);
            }
            case "patrol", "sprint" -> {
                key = "patrol";
                cooldown = 12_000;
                requireReady(actor, key, now);
                actor.cooldowns.put(key, now + cooldown);
                actor.speedUntil = now + PATROL_MILLIS;
                state.addEvent(now, "SKILL", actor.userId, "快速巡检：3 秒加速");
            }
            default -> throw GameException.badRequest(
                    "GAME_ARENA_ACTION_INVALID", "未知的巡检技能");
        }
    }

    private void placeBarrier(ExerciseState state, ExercisePlayer actor,
                              ExerciseInput input, long now) {
        double x = input.skillX() == null ? actor.x + Math.cos(actor.aim) * 90 : input.skillX();
        double y = input.skillY() == null ? actor.y + Math.sin(actor.aim) * 90 : input.skillY();
        long temporary = state.obstacles.stream()
                .filter(obstacle -> obstacle.expiresAt > 0)
                .count();
        if (temporary >= MAX_TEMPORARY_OBSTACLES) {
            state.obstacles.removeIf(obstacle -> obstacle.expiresAt > 0 && obstacle.expiresAt <= now);
        }
        Obstacle barrier = new Obstacle();
        barrier.x = clamp(x - 70, MARGIN, WIDTH - MARGIN - 140);
        barrier.y = clamp(y - 10, MARGIN, HEIGHT - MARGIN - 20);
        barrier.w = 140;
        barrier.h = 20;
        barrier.hard = true;
        barrier.expiresAt = now + BARRIER_MILLIS;
        state.obstacles.add(barrier);
        state.addEvent(now, "SKILL", actor.userId, "链路阻断屏障已部署");
    }

    private static void requireReady(ExercisePlayer actor, String key, long now) {
        Long readyAt = actor.cooldowns.get(key);
        if (readyAt != null && readyAt > now) {
            throw GameException.conflict("GAME_ARENA_COOLDOWN", "技能仍在冷却");
        }
    }

    private void fire(ExerciseState state, ExercisePlayer actor, long now) {
        if (now - actor.lastFireAt < FIRE_COOLDOWN) {
            return;
        }
        if (actor.shieldUntil > now) {
            return;
        }
        actor.lastFireAt = now;
        actor.shots += 1;
        double dirX = Math.cos(actor.aim);
        double dirY = Math.sin(actor.aim);
        ExercisePlayer target = null;
        double bestDistance = Double.MAX_VALUE;
        for (ExercisePlayer other : state.actors) {
            if (other == actor || !other.alive) {
                continue;
            }
            if (sameTeam(state, actor, other)) {
                continue;
            }
            double dx = other.x - actor.x;
            double dy = other.y - actor.y;
            double projection = dx * dirX + dy * dirY;
            if (projection <= 0 || projection > FIRE_RANGE) {
                continue;
            }
            double cross = Math.abs(dx * dirY - dy * dirX);
            if (cross > HIT_RADIUS) {
                continue;
            }
            if (projection < bestDistance) {
                double dot = (dx * dirX + dy * dirY) / Math.max(1e-6, Math.hypot(dx, dy));
                if (dot < CONE_COS) {
                    continue;
                }
                if (blocked(state, actor.x, actor.y, other.x, other.y, now, true)) {
                    continue;
                }
                bestDistance = projection;
                target = other;
            }
        }
        double endX = actor.x + dirX * FIRE_RANGE;
        double endY = actor.y + dirY * FIRE_RANGE;
        if (target != null) {
            endX = target.x;
            endY = target.y;
        } else {
            double[] stop = firstObstacleHit(state, actor.x, actor.y, endX, endY, now);
            if (stop != null) {
                endX = stop[0];
                endY = stop[1];
            }
        }
        addTracer(state, actor, endX, endY, target != null, now);
        if (target == null) {
            return;
        }
        actor.hits += 1;
        actor.score += 1;
        target.damageAt.put(actor.userId, now);
        if (target.shieldUntil > now) {
            state.addEvent(now, "HIT", actor.userId,
                    "命中 " + target.nickname + "，被隔离护盾吸收");
            return;
        }
        target.hp -= HIT_DAMAGE;
        if (target.hp > 0) {
            return;
        }
        target.hp = 0;
        target.alive = false;
        target.deaths += 1;
        target.respawnAt = now + (target.bot ? BOT_RESPAWN_MILLIS : RESPAWN_MILLIS);
        target.inputX = 0;
        target.inputY = 0;
        actor.kills += 1;
        actor.score += 5;
        state.addEvent(now, "KILL", actor.userId,
                actor.nickname + " 击倒 " + target.nickname + " +5");
        for (ExercisePlayer helper : state.actors) {
            if (helper == actor || helper == target) {
                continue;
            }
            Long damagedAt = target.damageAt.get(helper.userId);
            if (damagedAt != null && now - damagedAt <= ASSIST_WINDOW) {
                helper.assists += 1;
                helper.score += 2;
                state.addEvent(now, "ASSIST", helper.userId,
                        helper.nickname + " 协助击倒 +2");
            }
        }
        target.damageAt.clear();
    }

    private static boolean sameTeam(ExerciseState state, ExercisePlayer left, ExercisePlayer right) {
        return MODE_TEAM.equals(state.mode)
                && left.team != null && left.team.equals(right.team);
    }

    private static void addTracer(ExerciseState state, ExercisePlayer actor,
                                  double endX, double endY, boolean hit, long now) {
        Tracer tracer = new Tracer();
        tracer.userId = actor.userId;
        tracer.x1 = actor.x;
        tracer.y1 = actor.y;
        tracer.x2 = endX;
        tracer.y2 = endY;
        tracer.hit = hit;
        tracer.at = now;
        state.tracers.add(tracer);
        while (state.tracers.size() > 60) {
            state.tracers.remove(0);
        }
    }

    public void tick(ExerciseState state, long now) {
        if (state.finished) {
            return;
        }
        state.serverTime = now;
        long dtMillis = Math.max(0, Math.min(200, now - state.lastTickAt));
        state.lastTickAt = now;
        double dt = dtMillis / 1000.0;
        if (dt > 0) {
            for (ExercisePlayer actor : state.actors) {
                if (!actor.alive) {
                    if (now >= actor.respawnAt) {
                        respawn(state, actor, now);
                    }
                    continue;
                }
                move(state, actor, dt, now);
            }
        }
        updatePoints(state, now);
        state.tracers.removeIf(tracer -> now - tracer.at > 800);
        state.obstacles.removeIf(obstacle -> obstacle.expiresAt > 0 && obstacle.expiresAt <= now);
        if (now >= state.endsAt) {
            finishByScore(state, now);
        }
        state.seq += 1;
    }

    private void move(ExerciseState state, ExercisePlayer actor, double dt, long now) {
        double inputX = actor.inputX;
        double inputY = actor.inputY;
        if (now - actor.lastInputAt > 500) {
            inputX = 0;
            inputY = 0;
        }
        if (inputX == 0 && inputY == 0) {
            return;
        }
        double speed = actor.speedUntil > now ? SPRINT_SPEED : SPEED;
        if (insideSoft(state, actor.x, actor.y, now)) {
            speed = Math.min(speed, SOFT_SPEED);
        }
        double nextX = clamp(actor.x + inputX * speed * dt, MARGIN, WIDTH - MARGIN);
        double nextY = clamp(actor.y + inputY * speed * dt, MARGIN, HEIGHT - MARGIN);
        if (!collides(state, nextX, actor.y, now)) {
            actor.x = nextX;
        }
        if (!collides(state, actor.x, nextY, now)) {
            actor.y = nextY;
        }
    }

    private void respawn(ExerciseState state, ExercisePlayer actor, long now) {
        int index = state.actors.indexOf(actor);
        double[] spawn = SPAWNS.get(Math.max(0, index) % SPAWNS.size());
        actor.x = spawn[0];
        actor.y = spawn[1];
        actor.hp = 100;
        actor.alive = true;
        actor.respawnAt = 0;
        actor.shieldUntil = 0;
        actor.scanUntil = 0;
        actor.speedUntil = 0;
        actor.inputX = 0;
        actor.inputY = 0;
        actor.aim = Math.atan2(HEIGHT / 2 - actor.y, WIDTH / 2 - actor.x);
        state.addEvent(now, "RESPAWN", actor.userId, actor.nickname + " 重新接入巡检");
    }

    private void updatePoints(ExerciseState state, long now) {
        for (ControlPoint point : state.points) {
            List<ExercisePlayer> occupants = new ArrayList<>();
            for (ExercisePlayer actor : state.actors) {
                if (!actor.bot && actor.alive
                        && distance(actor.x, actor.y, point.x, point.y) <= CAPTURE_RADIUS) {
                    occupants.add(actor);
                }
            }
            for (ExercisePlayer actor : occupants) {
                if (point.resourceAt <= now
                        && distance(actor.x, actor.y, point.x, point.y) <= PICKUP_RADIUS) {
                    point.resourceAt = now + PICKUP_INTERVAL;
                    actor.score += 2;
                    state.addEvent(now, "PICKUP", actor.userId,
                            actor.nickname + " 拾取巡检样本 +2");
                }
            }
            if (occupants.size() == 1) {
                ExercisePlayer holder = occupants.get(0);
                if (!holder.userId.equals(point.holderUserId)) {
                    point.holderUserId = holder.userId;
                    point.holdSince = now;
                    point.lastIncomeAt = now;
                }
                if (now - point.holdSince >= CAPTURE_INTERVAL
                        && now - point.lastIncomeAt >= CAPTURE_INTERVAL) {
                    point.lastIncomeAt = now;
                    point.ownerUserId = holder.userId;
                    point.ownerTeam = holder.team;
                    holder.score += 1;
                    state.addEvent(now, "CAPTURE", holder.userId,
                            holder.nickname + " 稳住 " + point.id + " +1");
                }
            } else {
                point.holderUserId = null;
                point.holdSince = 0;
            }
        }
    }

    private void finishByScore(ExerciseState state, long now) {
        if (state.actors.isEmpty()) {
            state.finish(now, null, "巡检结束");
            return;
        }
        String winner = null;
        String winnerTeam = null;
        String reason;
        if (MODE_TRAINING.equals(state.mode)) {
            ExercisePlayer human = state.actors.stream()
                    .filter(actor -> !actor.bot)
                    .findFirst().orElse(state.actors.get(0));
            winner = human.userId;
            reason = "训练结束 · 命中 " + human.hits + " · 击倒 " + human.kills;
        } else if (MODE_TEAM.equals(state.mode)) {
            int teamAScore = teamScore(state, "A");
            int teamBScore = teamScore(state, "B");
            if (teamAScore > teamBScore) {
                winnerTeam = "A";
            } else if (teamBScore > teamAScore) {
                winnerTeam = "B";
            } else {
                winnerTeam = teamKills(state, "A") >= teamKills(state, "B") ? "A" : "B";
            }
            winner = bestActor(state, winnerTeam).map(actor -> actor.userId).orElse(null);
            reason = "团队积分 " + teamAScore + " : " + teamBScore;
        } else {
            winner = bestActor(state, null).map(actor -> actor.userId).orElse(null);
            reason = "巡检积分领先";
        }
        state.winnerTeam = winnerTeam;
        state.status = "FINISHED";
        state.finish(now, winner, reason);
        state.addEvent(now, "RESULT", winner, "演练结束：" + reason);
    }

    private static int teamScore(ExerciseState state, String team) {
        return state.actors.stream()
                .filter(actor -> Objects.equals(team, actor.team))
                .mapToInt(actor -> actor.score)
                .sum();
    }

    private static int teamKills(ExerciseState state, String team) {
        return state.actors.stream()
                .filter(actor -> Objects.equals(team, actor.team))
                .mapToInt(actor -> actor.kills)
                .sum();
    }

    private static java.util.Optional<ExercisePlayer> bestActor(ExerciseState state, String team) {
        return state.actors.stream()
                .filter(actor -> team == null || Objects.equals(team, actor.team))
                .max(Comparator
                        .comparingInt((ExercisePlayer actor) -> actor.score)
                        .thenComparingInt(actor -> actor.kills)
                        .thenComparingInt(actor -> -state.actors.indexOf(actor)));
    }

    /** 服务端视野过滤：自身始终可见，其余目标需在感知范围内且无硬掩体遮挡。 */
    public List<ExercisePlayer> visibleActors(ExerciseState state, String viewerUserId, long now) {
        ExercisePlayer viewer = actor(state, viewerUserId);
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
            double distance = distance(viewer.x, viewer.y, candidate.x, candidate.y);
            if (distance <= properties.perceptionRange()
                    && !blocked(state, viewer.x, viewer.y, candidate.x, candidate.y, now, false)) {
                visible.add(candidate);
            }
        }
        return visible;
    }

    private static ExercisePlayer actor(ExerciseState state, String userId) {
        if (userId == null) {
            return null;
        }
        return state.actors.stream()
                .filter(actor -> userId.equals(actor.userId))
                .findFirst().orElse(null);
    }

    private static boolean collides(ExerciseState state, double x, double y, long now) {
        for (Obstacle obstacle : state.obstacles) {
            if (!obstacle.hard || expired(obstacle, now)) {
                continue;
            }
            if (circleHitsRect(x, y, PLAYER_RADIUS, obstacle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean insideSoft(ExerciseState state, double x, double y, long now) {
        for (Obstacle obstacle : state.obstacles) {
            if (obstacle.hard || expired(obstacle, now)) {
                continue;
            }
            if (x >= obstacle.x && x <= obstacle.x + obstacle.w
                    && y >= obstacle.y && y <= obstacle.y + obstacle.h) {
                return true;
            }
        }
        return false;
    }

    private static boolean blocked(ExerciseState state, double x1, double y1,
                                   double x2, double y2, long now, boolean includeSoft) {
        for (Obstacle obstacle : state.obstacles) {
            if (expired(obstacle, now) || (!obstacle.hard && !includeSoft)) {
                continue;
            }
            if (segmentHitsRect(x1, y1, x2, y2, obstacle)) {
                return true;
            }
        }
        return false;
    }

    private static double[] firstObstacleHit(ExerciseState state, double x1, double y1,
                                             double x2, double y2, long now) {
        double best = Double.MAX_VALUE;
        double[] result = null;
        for (Obstacle obstacle : state.obstacles) {
            if (expired(obstacle, now)) {
                continue;
            }
            double t = segmentRectEntry(x1, y1, x2, y2, obstacle);
            if (t >= 0 && t < best) {
                best = t;
                result = new double[] {x1 + (x2 - x1) * t, y1 + (y2 - y1) * t};
            }
        }
        return result;
    }

    private static boolean expired(Obstacle obstacle, long now) {
        return obstacle.expiresAt > 0 && obstacle.expiresAt <= now;
    }

    static boolean circleHitsRect(double cx, double cy, double radius, Obstacle rect) {
        double closestX = clamp(cx, rect.x, rect.x + rect.w);
        double closestY = clamp(cy, rect.y, rect.y + rect.h);
        return Math.hypot(cx - closestX, cy - closestY) < radius;
    }

    /** 线段与矩形相交：用于视线与子弹遮挡判定。 */
    static boolean segmentHitsRect(double x1, double y1, double x2, double y2, Obstacle rect) {
        return segmentRectEntry(x1, y1, x2, y2, rect) >= 0;
    }

    /** 返回线段进入矩形的参数 t（0-1），不相交返回 -1。 */
    static double segmentRectEntry(double x1, double y1, double x2, double y2, Obstacle rect) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double tMin = 0;
        double tMax = 1;
        double[][] bounds = {
                {x1, dx, rect.x, rect.x + rect.w},
                {y1, dy, rect.y, rect.y + rect.h}
        };
        for (double[] bound : bounds) {
            double origin = bound[0];
            double direction = bound[1];
            double min = bound[2];
            double max = bound[3];
            if (Math.abs(direction) < 1e-9) {
                if (origin < min || origin > max) {
                    return -1;
                }
                continue;
            }
            double t1 = (min - origin) / direction;
            double t2 = (max - origin) / direction;
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return -1;
            }
        }
        return tMin;
    }

    private static double distance(double x1, double y1, double x2, double y2) {
        return Math.hypot(x1 - x2, y1 - y2);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double normalizeAngle(double angle) {
        double value = angle % (Math.PI * 2);
        return value < 0 ? value + Math.PI * 2 : value;
    }

    private static final List<double[]> SPAWNS = List.of(
            new double[] {120, 120},
            new double[] {1160, 120},
            new double[] {120, 600},
            new double[] {1160, 600});

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
