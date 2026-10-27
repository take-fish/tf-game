package com.fishmatrix.game.store;

import com.fishmatrix.game.model.LeaderboardEntry;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 大排行榜与赛事统计。大榜只认赛事头衔：
 * 冠军 30 分、亚军 10 分；对局内得分仅用于单场判定，不进入大榜。
 * matches/wins/losses/best_score 继续保留作辅助统计，不影响大榜排序。
 */
@Repository
public class PlayerStatRepository {

    public static final int CHAMPION_POINTS = 30;
    public static final int RUNNER_UP_POINTS = 10;

    private final JdbcTemplate jdbc;

    public PlayerStatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensure(String userId, String nickname) {
        long now = System.currentTimeMillis();
        int updated = jdbc.update(
                "UPDATE game_player_stat SET nickname = ?, updated_at = ? WHERE user_id = ?",
                nickname, SqlTime.ts(now), userId);
        if (updated > 0) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO game_player_stat
                        (user_id, nickname, matches, wins, losses, points, championships,
                         runner_ups, best_score, updated_at)
                    VALUES (?, ?, 0, 0, 0, 0, 0, 0, 0, ?)
                    """, userId, nickname, SqlTime.ts(now));
        } catch (DuplicateKeyException ignored) {
            // 并发首次写入：另一个请求已经插入，更新昵称即可。
            jdbc.update(
                    "UPDATE game_player_stat SET nickname = ?, updated_at = ? WHERE user_id = ?",
                    nickname, SqlTime.ts(now), userId);
        }
    }

    /** 赛事结束时一次性记录头衔；积分只由冠军/亚军折算，不按场次累加。 */
    public void recordTitle(String userId, String nickname, boolean champion) {
        ensure(userId, nickname);
        int pointDelta = champion ? CHAMPION_POINTS : RUNNER_UP_POINTS;
        jdbc.update("""
                UPDATE game_player_stat
                   SET championships = championships + ?,
                       runner_ups = runner_ups + ?,
                       points = points + ?,
                       nickname = ?,
                       updated_at = ?
                 WHERE user_id = ?
                """,
                champion ? 1 : 0, champion ? 0 : 1, pointDelta,
                nickname, SqlTime.ts(System.currentTimeMillis()), userId);
    }

    /** 保留对局统计，但不再给大榜加任何分数。 */
    public void recordMatch(String userId, String nickname, boolean win, boolean champion) {
        ensure(userId, nickname);
        jdbc.update("""
                UPDATE game_player_stat
                   SET matches = matches + 1,
                       wins = wins + ?,
                       losses = losses + ?,
                       nickname = ?,
                       updated_at = ?
                 WHERE user_id = ?
                """,
                win ? 1 : 0, win ? 0 : 1,
                nickname, SqlTime.ts(System.currentTimeMillis()), userId);
    }

    /** 打砖块只保留最高分，不折算大榜积分。 */
    public void recordBreakoutScore(String userId, String nickname, long score) {
        ensure(userId, nickname);
        long previous = bestScore(userId);
        long newBest = Math.max(previous, score);
        jdbc.update("""
                UPDATE game_player_stat
                   SET best_score = ?, nickname = ?, updated_at = ?
                 WHERE user_id = ?
                """, newBest, nickname, SqlTime.ts(System.currentTimeMillis()), userId);
    }

    public long bestScore(String userId) {
        Long value = jdbc.query(
                "SELECT best_score FROM game_player_stat WHERE user_id = ?",
                rs -> rs.next() ? rs.getLong(1) : 0L,
                userId);
        return value == null ? 0L : value;
    }

    public int points(String userId) {
        Integer value = jdbc.query(
                "SELECT points FROM game_player_stat WHERE user_id = ?",
                rs -> rs.next() ? rs.getInt(1) : 0,
                userId);
        return value == null ? 0 : value;
    }

    public List<LeaderboardEntry> top(int limit) {
        int safeLimit = Math.min(50, Math.max(1, limit));
        return jdbc.query("""
                SELECT user_id, nickname, championships, runner_ups, points
                  FROM game_player_stat
                 ORDER BY points DESC, championships DESC, runner_ups DESC, updated_at ASC
                 LIMIT ?
                """, (rs, rowNum) -> new LeaderboardEntry(
                rowNum + 1L,
                rs.getString("user_id"),
                rs.getString("nickname"),
                rs.getInt("championships"),
                rs.getInt("runner_ups"),
                rs.getInt("points")), safeLimit);
    }

    public long rank(String userId) {
        Long rank = jdbc.query("""
                SELECT position FROM (
                    SELECT user_id,
                           ROW_NUMBER() OVER (
                               ORDER BY points DESC, championships DESC,
                                        runner_ups DESC, updated_at ASC
                           ) AS position
                      FROM game_player_stat
                ) ranked
                WHERE user_id = ?
                """, rs -> rs.next() ? rs.getLong(1) : 0L, userId);
        return rank == null ? 0L : rank;
    }
}
