package com.fishmatrix.game.model;

/**
 * 巡检对抗战绩行。直接作为 {@code /api/v1/game/exercise/results} 的返回元素，
 * 字段名与顺序保持迁移前不变，避免前端 JSON 契约变化。
 */
public record ExerciseResultRecord(
        String id,
        String roomId,
        String mode,
        String userId,
        String nickname,
        String team,
        int rankNo,
        int score,
        int kills,
        int deaths,
        int assists,
        boolean winner,
        long createdAt) {
}
