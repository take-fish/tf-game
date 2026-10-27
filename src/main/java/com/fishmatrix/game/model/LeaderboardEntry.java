package com.fishmatrix.game.model;

/**
 * 大排行榜条目：只展示名号、ID、冠军数、亚军数与总积分。
 * 总积分由赛事头衔折算：冠军 30 分，亚军 10 分；对局内得分不进入大榜。
 */
public record LeaderboardEntry(
        long rank,
        String userId,
        String nickname,
        int championships,
        int runnerUps,
        int points) {
}
