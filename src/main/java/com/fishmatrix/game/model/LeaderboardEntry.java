package com.fishmatrix.game.model;

/**
 * 排行榜条目。大排行榜只展示名号、ID、冠军数、亚军数与总积分：
 * 总积分由赛事头衔折算（冠军 30 分、亚军 10 分），对局内得分不进入大榜。
 * score/matches/wins/losses/bestScore 保留给分游戏榜与旧前端兼容。
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
        int runnerUps,
        long bestScore) {

    /** 大排行榜核心字段。 */
    public LeaderboardEntry(
            long rank, String userId, String nickname,
            int championships, int runnerUps, int points) {
        this(rank, userId, nickname, points, points, 0, 0, 0,
                championships, runnerUps, 0L);
    }

    /** 兼容旧调用：只提供分数。 */
    public LeaderboardEntry(long rank, String userId, String nickname, long score) {
        this(rank, userId, nickname, score, (int) score, 0, 0, 0, 0, 0, 0L);
    }

    /** 兼容分游戏榜旧字段顺序。 */
    public LeaderboardEntry(
            long rank, String userId, String nickname, long score, int points,
            int matches, int wins, int losses, int championships, long bestScore) {
        this(rank, userId, nickname, score, points, matches, wins, losses,
                championships, 0, bestScore);
    }
}
