package com.fishmatrix.game.arena;

import java.util.List;

/**
 * 巡检演练的几何与碰撞工具：玩家、掩体、视线与子弹的判定基础。
 */
final class ExerciseGeometry {

    static final double WIDTH = 1280;
    static final double HEIGHT = 720;
    static final double MARGIN = 26;
    static final double PLAYER_RADIUS = 14;

    static final List<double[]> SPAWNS = List.of(
            new double[] {120, 120},
            new double[] {1160, 120},
            new double[] {120, 600},
            new double[] {1160, 600});

    private ExerciseGeometry() {
    }

    static boolean collides(ExerciseEngine.ExerciseState state, double x, double y, long now) {
        for (ExerciseEngine.Obstacle obstacle : state.obstacles) {
            if (!obstacle.hard || expired(obstacle, now)) {
                continue;
            }
            if (circleHitsRect(x, y, PLAYER_RADIUS, obstacle)) {
                return true;
            }
        }
        return false;
    }

    static boolean insideSoft(ExerciseEngine.ExerciseState state, double x, double y, long now) {
        for (ExerciseEngine.Obstacle obstacle : state.obstacles) {
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

    static boolean blocked(ExerciseEngine.ExerciseState state, double x1, double y1,
                           double x2, double y2, long now, boolean includeSoft) {
        for (ExerciseEngine.Obstacle obstacle : state.obstacles) {
            if (expired(obstacle, now) || (!obstacle.hard && !includeSoft)) {
                continue;
            }
            if (segmentHitsRect(x1, y1, x2, y2, obstacle)) {
                return true;
            }
        }
        return false;
    }

    static double[] firstObstacleHit(ExerciseEngine.ExerciseState state, double x1, double y1,
                                     double x2, double y2, long now) {
        double best = Double.MAX_VALUE;
        double[] result = null;
        for (ExerciseEngine.Obstacle obstacle : state.obstacles) {
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

    static boolean expired(ExerciseEngine.Obstacle obstacle, long now) {
        return obstacle.expiresAt > 0 && obstacle.expiresAt <= now;
    }

    static boolean circleHitsRect(double cx, double cy, double radius,
                                  ExerciseEngine.Obstacle rect) {
        double closestX = clamp(cx, rect.x, rect.x + rect.w);
        double closestY = clamp(cy, rect.y, rect.y + rect.h);
        return Math.hypot(cx - closestX, cy - closestY) < radius;
    }

    /** 线段与矩形相交：用于视线与子弹遮挡判定。 */
    static boolean segmentHitsRect(double x1, double y1, double x2, double y2,
                                   ExerciseEngine.Obstacle rect) {
        return segmentRectEntry(x1, y1, x2, y2, rect) >= 0;
    }

    /** 返回线段进入矩形的参数 t（0-1），不相交返回 -1。 */
    static double segmentRectEntry(double x1, double y1, double x2, double y2,
                                   ExerciseEngine.Obstacle rect) {
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

    static double distance(double x1, double y1, double x2, double y2) {
        return Math.hypot(x1 - x2, y1 - y2);
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    static double normalizeAngle(double angle) {
        double value = angle % (Math.PI * 2);
        return value < 0 ? value + Math.PI * 2 : value;
    }
}
