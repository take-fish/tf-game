package com.fishmatrix.game.model;

/**
 * 综合排行榜条目。score 与 points 保持同值，兼容旧前端字段；
 * 其余字段用于展示对局数、冠军数和打砖块最高分。
 */
public record LeaderboardEntry(
        long rank,
        String userId,
        String nickname,
        long score,
        int points,
        int matches,
        int wins,
        int losses,
        int championships,
        long bestScore) {

    public LeaderboardEntry(long rank, String userId, String nickname, long score) {
        this(rank, userId, nickname, score, (int) score, 0, 0, 0, 0, 0);
    }
}
