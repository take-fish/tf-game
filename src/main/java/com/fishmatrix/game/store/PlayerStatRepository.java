package com.fishmatrix.game.store;

import com.fishmatrix.game.model.LeaderboardEntry;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 排行榜数据：game_player_stat 为综合大榜；game_player_game_stat 按游戏拆分的棋类榜。
 * 只负责持久化；积分与头衔规则在 {@code PlayerStatRules}，由 Service 计算增量后传入。
 * matches/wins/losses/best_score 继续保留作辅助统计与分游戏榜展示。
 */
@RequiredArgsConstructor
@Repository
public class PlayerStatRepository {

    private final JdbcTemplate jdbc;

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

    /** 按增量累计赛事头衔；头衔与积分由 PlayerStatRules 计算后传入。 */
    public void addTitle(String userId, String nickname, int championships, int runnerUps, int points) {
        ensure(userId, nickname);
        jdbc.update("""
                UPDATE game_player_stat
                   SET championships = championships + ?,
                       runner_ups = runner_ups + ?,
                       points = points + ?,
                       nickname = ?,
                       updated_at = ?
                 WHERE user_id = ?
                """, championships, runnerUps, points,
                nickname, SqlTime.ts(System.currentTimeMillis()), userId);
    }

    /** 按增量累计对局统计，大榜积分不随场次增加。 */
    public void addMatch(String userId, String nickname, int wins, int losses) {
        ensure(userId, nickname);
        jdbc.update("""
                UPDATE game_player_stat
                   SET matches = matches + 1,
                       wins = wins + ?,
                       losses = losses + ?,
                       nickname = ?,
                       updated_at = ?
                 WHERE user_id = ?
                """, wins, losses,
                nickname, SqlTime.ts(System.currentTimeMillis()), userId);
    }

    /** 按增量累计分游戏榜的场次、积分与冠军次数。 */
    public void addGameMatch(String gameId, String userId, String nickname,
                             int wins, int losses, int points, int championships) {
        ensureGame(gameId, userId, nickname);
        jdbc.update("""
                UPDATE game_player_game_stat
                   SET matches = matches + 1,
                       wins = wins + ?,
                       losses = losses + ?,
                       points = points + ?,
                       championships = championships + ?,
                       nickname = ?,
                       updated_at = ?
                 WHERE game_id = ? AND user_id = ?
                """, wins, losses, points, championships,
                nickname, SqlTime.ts(System.currentTimeMillis()), gameId, userId);
    }

    /** 某一游戏的棋类排行榜。 */
    public List<LeaderboardEntry> topByGame(String gameId, int limit) {
        int safeLimit = Math.min(50, Math.max(1, limit));
        return jdbc.query("""
                SELECT user_id, nickname, points, matches, wins, losses, championships
                  FROM game_player_game_stat
                 WHERE game_id = ?
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
                0L), gameId, safeLimit);
    }

    /**
     * 打砖块只保留更高分，不折算大榜积分。
     * 用数据库侧 GREATEST 原子比较，避免并发读改写把更高的历史分覆盖掉。
     */
    public void raiseBestScore(String userId, String nickname, long score) {
        ensure(userId, nickname);
        jdbc.update("""
                UPDATE game_player_stat
                   SET best_score = GREATEST(best_score, ?), nickname = ?, updated_at = ?
                 WHERE user_id = ?
                """, score, nickname, SqlTime.ts(System.currentTimeMillis()), userId);
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

    private void ensureGame(String gameId, String userId, String nickname) {
        long now = System.currentTimeMillis();
        int updated = jdbc.update("""
                UPDATE game_player_game_stat
                   SET nickname = ?, updated_at = ?
                 WHERE game_id = ? AND user_id = ?
                """, nickname, SqlTime.ts(now), gameId, userId);
        if (updated > 0) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO game_player_game_stat
                        (game_id, user_id, nickname, matches, wins, losses, points,
                         championships, updated_at)
                    VALUES (?, ?, ?, 0, 0, 0, 0, 0, ?)
                    """, gameId, userId, nickname, SqlTime.ts(now));
        } catch (DuplicateKeyException ignored) {
            jdbc.update("""
                    UPDATE game_player_game_stat
                       SET nickname = ?, updated_at = ?
                     WHERE game_id = ? AND user_id = ?
                    """, nickname, SqlTime.ts(now), gameId, userId);
        }
    }
}
