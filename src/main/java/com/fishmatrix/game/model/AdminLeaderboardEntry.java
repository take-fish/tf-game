package com.fishmatrix.game.model;

/**
 * 管理台排行榜行：在对外榜单字段之外，额外带可见性、展示顺序与操作审计，
 * 只给管理员接口使用，公开榜单仍返回 {@link LeaderboardEntry}。
 */
public record AdminLeaderboardEntry(
        long rank,
        String userId,
        String nickname,
        String gameId,
        int matches,
        int wins,
        int losses,
        int points,
        int championships,
        int runnerUps,
        long bestScore,
        boolean visible,
        int displayOrder,
        String managedBy,
        Long managedAt) {
}
