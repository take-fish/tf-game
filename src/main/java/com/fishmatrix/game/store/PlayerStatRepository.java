package com.fishmatrix.game.store;

import com.fishmatrix.game.model.LeaderboardEntry;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 综合排行榜。所有游戏共用一张表：
 * 五子棋按胜场/冠军累计积分，打砖块按最高分折算积分。
 */
@Repository
public class PlayerStatRepository {

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
                        (user_id, nickname, matches, wins, losses, points, championships, best_score, updated_at)
                    VALUES (?, ?, 0, 0, 0, 0, 0, 0, ?)
                    """, userId, nickname, SqlTime.ts(now));
        } catch (DuplicateKeyException ignored) {
            // 并发首次写入：另一个请求已经插入，更新昵称即可。
            jdbc.update(
                    "UPDATE game_player_stat SET nickname = ?, updated_at = ? WHERE user_id = ?",
                    nickname, SqlTime.ts(now), userId);
        }
    }

    /** 一场完整比赛结束后记一次参与；冠军额外奖励。 */
    public void recordMatch(String userId, String nickname, boolean win, boolean champion) {
        ensure(userId, nickname);
        int points = (win ? 3 : 1) + (champion ? 10 : 0);
        jdbc.update("""
                UPDATE game_player_stat
                   SET matches = matches + 1,
                       wins = wins + ?,
                       losses = losses + ?,
                       points = points + ?,
                       championships = championships + ?,
                       nickname = ?,
                       updated_at = ?
                 WHERE user_id = ?
                """,
                win ? 1 : 0, win ? 0 : 1, points, champion ? 1 : 0,
                nickname, SqlTime.ts(System.currentTimeMillis()), userId);
    }

    /** 打砖块只保留最高分；每 100 分折算 1 点综合积分，只奖励首次刷新部分。 */
    public void recordBreakoutScore(String userId, String nickname, long score) {
        ensure(userId, nickname);
        long previous = bestScore(userId);
        long newBest = Math.max(previous, score);
        long delta = Math.max(0, score / 100 - previous / 100);
        jdbc.update("""
                UPDATE game_player_stat
                   SET best_score = ?, points = points + ?, nickname = ?, updated_at = ?
                 WHERE user_id = ?
                """, newBest, delta, nickname, SqlTime.ts(System.currentTimeMillis()), userId);
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
                SELECT user_id, nickname, points, matches, wins, losses, championships, best_score
                  FROM game_player_stat
                 ORDER BY points DESC, championships DESC, wins DESC, updated_at ASC
                 LIMIT ?
                """, (rs, rowNum) -> new LeaderboardEntry(
                rowNum + 1L,
                rs.getString("user_id"),
                rs.getString("nickname"),
                rs.getInt("points"),
                rs.getInt("points"),
                rs.getInt("matches"),
                rs.getInt("wins"),
                rs.getInt("losses"),
                rs.getInt("championships"),
                rs.getLong("best_score")), safeLimit);
    }

    public long rank(String userId) {
        Long rank = jdbc.query("""
                SELECT position FROM (
                    SELECT user_id,
                           ROW_NUMBER() OVER (
                               ORDER BY points DESC, championships DESC, wins DESC, updated_at ASC
                           ) AS position
                      FROM game_player_stat
                ) ranked
                WHERE user_id = ?
                """, rs -> rs.next() ? rs.getLong(1) : 0L, userId);
        return rank == null ? 0L : rank;
    }
}
