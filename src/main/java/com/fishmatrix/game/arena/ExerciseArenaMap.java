package com.fishmatrix.game.arena;

import java.util.List;

/**
 * 巡检地图：1280×720、四个出生点、五处资源/控制点与硬/软掩体。
 */
final class ExerciseArenaMap {

    private ExerciseArenaMap() {
    }

    static void build(ExerciseEngine.ExerciseState state, long startedAt) {
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
            ExerciseEngine.ControlPoint control = new ExerciseEngine.ControlPoint();
            control.id = "cp-" + (index + 1);
            control.x = point[0];
            control.y = point[1];
            control.resourceAt = startedAt + 3000;
            control.lastIncomeAt = startedAt;
            state.points.add(control);
        }
    }

    static int spawnIndexFor(ExerciseEngine.ExerciseState state,
                             ExerciseEngine.PlayerSeed entry, int index) {
        if (ExerciseEngine.MODE_TRAINING.equals(state.mode) && entry.bot()) {
            return 1 + (index % 3);
        }
        if (ExerciseEngine.MODE_TEAM.equals(state.mode) && "B".equals(entry.team())) {
            return index % 2 == 0 ? 1 : 3;
        }
        if (ExerciseEngine.MODE_TEAM.equals(state.mode)) {
            return index % 2 == 0 ? 0 : 2;
        }
        return index % 4;
    }

    private static void addHard(ExerciseEngine.ExerciseState state,
                                double x, double y, double w, double h) {
        ExerciseEngine.Obstacle obstacle = new ExerciseEngine.Obstacle();
        obstacle.x = x;
        obstacle.y = y;
        obstacle.w = w;
        obstacle.h = h;
        obstacle.hard = true;
        state.obstacles.add(obstacle);
    }

    private static void addSoft(ExerciseEngine.ExerciseState state,
                                double x, double y, double w, double h) {
        ExerciseEngine.Obstacle obstacle = new ExerciseEngine.Obstacle();
        obstacle.x = x;
        obstacle.y = y;
        obstacle.w = w;
        obstacle.h = h;
        obstacle.hard = false;
        state.obstacles.add(obstacle);
    }
}
