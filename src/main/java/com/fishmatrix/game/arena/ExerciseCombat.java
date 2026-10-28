package com.fishmatrix.game.arena;

import com.fishmatrix.game.arena.ExerciseEngine.ExercisePlayer;
import com.fishmatrix.game.arena.ExerciseEngine.ExerciseState;
import com.fishmatrix.game.model.GameException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 巡检对抗的实时结算：移动、开火、技能、复活、控制点收益与终局判定。
 */
final class ExerciseCombat {

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
    private static final int MAX_TRACERS = 60;
    private static final long TRACER_LINGER = 800;
    private static final long INPUT_STALE = 500;
    private static final long MAX_TICK_MILLIS = 200;

    /** 客户端输入只更新意图；移动、开火与技能结算都在 tick 中完成。 */
    void input(ExerciseState state, String userId, ExerciseInput input, long now) {
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
        double moveX = ExerciseGeometry.clamp(input.moveX() == null ? 0 : input.moveX(), -1, 1);
        double moveY = ExerciseGeometry.clamp(input.moveY() == null ? 0 : input.moveY(), -1, 1);
        double length = Math.hypot(moveX, moveY);
        if (length > 1) {
            moveX /= length;
            moveY /= length;
        }
        actor.inputX = moveX;
        actor.inputY = moveY;
        actor.lastInputAt = now;
        if (input.aim() != null) {
            actor.aim = ExerciseGeometry.normalizeAngle(input.aim());
        }
        if (input.skill() != null && !input.skill().isBlank()) {
            useSkill(state, actor, input, now);
        }
        if (Boolean.TRUE.equals(input.fire())) {
            fire(state, actor, now);
        }
        state.seq += 1;
    }

    void tick(ExerciseState state, long now) {
        if (state.finished) {
            return;
        }
        state.serverTime = now;
        long dtMillis = Math.max(0, Math.min(MAX_TICK_MILLIS, now - state.lastTickAt));
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
        state.tracers.removeIf(tracer -> now - tracer.at > TRACER_LINGER);
        state.obstacles.removeIf(obstacle ->
                obstacle.expiresAt > 0 && obstacle.expiresAt <= now);
        if (now >= state.endsAt) {
            finishByScore(state, now);
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
        double x = input.skillX() == null
                ? actor.x + Math.cos(actor.aim) * 90 : input.skillX();
        double y = input.skillY() == null
                ? actor.y + Math.sin(actor.aim) * 90 : input.skillY();
        long temporary = state.obstacles.stream()
                .filter(obstacle -> obstacle.expiresAt > 0)
                .count();
        if (temporary >= MAX_TEMPORARY_OBSTACLES) {
            state.obstacles.removeIf(obstacle ->
                    obstacle.expiresAt > 0 && obstacle.expiresAt <= now);
        }
        ExerciseEngine.Obstacle barrier = new ExerciseEngine.Obstacle();
        barrier.x = ExerciseGeometry.clamp(x - 70, ExerciseGeometry.MARGIN,
                ExerciseGeometry.WIDTH - ExerciseGeometry.MARGIN - 140);
        barrier.y = ExerciseGeometry.clamp(y - 10, ExerciseGeometry.MARGIN,
                ExerciseGeometry.HEIGHT - ExerciseGeometry.MARGIN - 20);
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
                if (ExerciseGeometry.blocked(
                        state, actor.x, actor.y, other.x, other.y, now, true)) {
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
            double[] stop = ExerciseGeometry.firstObstacleHit(
                    state, actor.x, actor.y, endX, endY, now);
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

    private static boolean sameTeam(ExerciseState state, ExercisePlayer left,
                                    ExercisePlayer right) {
        return ExerciseEngine.MODE_TEAM.equals(state.mode)
                && left.team != null && left.team.equals(right.team);
    }

    private static void addTracer(ExerciseState state, ExercisePlayer actor,
                                  double endX, double endY, boolean hit, long now) {
        ExerciseEngine.Tracer tracer = new ExerciseEngine.Tracer();
        tracer.userId = actor.userId;
        tracer.x1 = actor.x;
        tracer.y1 = actor.y;
        tracer.x2 = endX;
        tracer.y2 = endY;
        tracer.hit = hit;
        tracer.at = now;
        state.tracers.add(tracer);
        while (state.tracers.size() > MAX_TRACERS) {
            state.tracers.remove(0);
        }
    }

    private void move(ExerciseState state, ExercisePlayer actor, double dt, long now) {
        double inputX = actor.inputX;
        double inputY = actor.inputY;
        if (now - actor.lastInputAt > INPUT_STALE) {
            inputX = 0;
            inputY = 0;
        }
        if (inputX == 0 && inputY == 0) {
            return;
        }
        double speed = actor.speedUntil > now ? SPRINT_SPEED : SPEED;
        if (ExerciseGeometry.insideSoft(state, actor.x, actor.y, now)) {
            speed = Math.min(speed, SOFT_SPEED);
        }
        double nextX = ExerciseGeometry.clamp(actor.x + inputX * speed * dt,
                ExerciseGeometry.MARGIN, ExerciseGeometry.WIDTH - ExerciseGeometry.MARGIN);
        double nextY = ExerciseGeometry.clamp(actor.y + inputY * speed * dt,
                ExerciseGeometry.MARGIN, ExerciseGeometry.HEIGHT - ExerciseGeometry.MARGIN);
        if (!ExerciseGeometry.collides(state, nextX, actor.y, now)) {
            actor.x = nextX;
        }
        if (!ExerciseGeometry.collides(state, actor.x, nextY, now)) {
            actor.y = nextY;
        }
    }

    private void respawn(ExerciseState state, ExercisePlayer actor, long now) {
        int index = state.actors.indexOf(actor);
        List<double[]> spawns = ExerciseGeometry.SPAWNS;
        double[] spawn = spawns.get(Math.max(0, index) % spawns.size());
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
        actor.aim = Math.atan2(ExerciseGeometry.HEIGHT / 2 - actor.y,
                ExerciseGeometry.WIDTH / 2 - actor.x);
        state.addEvent(now, "RESPAWN", actor.userId, actor.nickname + " 重新接入巡检");
    }

    private void updatePoints(ExerciseState state, long now) {
        for (ExerciseEngine.ControlPoint point : state.points) {
            List<ExercisePlayer> occupants = new ArrayList<>();
            for (ExercisePlayer actor : state.actors) {
                if (!actor.bot && actor.alive
                        && ExerciseGeometry.distance(
                                actor.x, actor.y, point.x, point.y) <= CAPTURE_RADIUS) {
                    occupants.add(actor);
                }
            }
            for (ExercisePlayer actor : occupants) {
                if (point.resourceAt <= now
                        && ExerciseGeometry.distance(
                                actor.x, actor.y, point.x, point.y) <= PICKUP_RADIUS) {
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
        if (ExerciseEngine.MODE_TRAINING.equals(state.mode)) {
            ExercisePlayer human = state.actors.stream()
                    .filter(actor -> !actor.bot)
                    .findFirst().orElse(state.actors.get(0));
            winner = human.userId;
            reason = "训练结束 · 命中 " + human.hits + " · 击倒 " + human.kills;
        } else if (ExerciseEngine.MODE_TEAM.equals(state.mode)) {
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

    private static Optional<ExercisePlayer> bestActor(ExerciseState state, String team) {
        return state.actors.stream()
                .filter(actor -> team == null || Objects.equals(team, actor.team))
                .max(Comparator
                        .comparingInt((ExercisePlayer actor) -> actor.score)
                        .thenComparingInt(actor -> actor.kills)
                        .thenComparingInt(actor -> -state.actors.indexOf(actor)));
    }

    static ExercisePlayer actor(ExerciseState state, String userId) {
        if (userId == null) {
            return null;
        }
        return state.actors.stream()
                .filter(actor -> userId.equals(actor.userId))
                .findFirst().orElse(null);
    }
}
