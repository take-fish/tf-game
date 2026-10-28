package com.fishmatrix.game.store;

import com.fishmatrix.game.model.NotificationView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@RequiredArgsConstructor
@Repository
public class NotificationRepository {

    private static final RowMapper<NotificationView> MAPPER = (rs, rowNum) -> new NotificationView(
            rs.getString("id"),
            rs.getString("type"),
            rs.getString("tournament_id"),
            rs.getString("match_id"),
            rs.getString("title"),
            rs.getString("message"),
            SqlTime.millis(rs, "created_at"),
            SqlTime.millis(rs, "expires_at"));

    private final JdbcTemplate jdbc;

    public void insert(NotificationView notification) {
        jdbc.update("""
                INSERT INTO game_notification
                    (id, type, tournament_id, match_id, title, message, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                notification.id(), notification.type(), notification.tournamentId(),
                notification.matchId(), notification.title(), notification.message(),
                SqlTime.ts(notification.createdAt()), SqlTime.ts(notification.expiresAt()));
    }

    /** 同一场比赛的同类通知只发一次，避免决赛/冠军重复弹窗。 */
    public boolean exists(String type, String tournamentId, String matchId) {
        Integer count;
        if (matchId == null) {
            count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM game_notification
                     WHERE type = ? AND tournament_id = ? AND match_id IS NULL
                    """, Integer.class, type, tournamentId);
        } else {
            count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM game_notification
                     WHERE type = ? AND tournament_id = ? AND match_id = ?
                    """, Integer.class, type, tournamentId, matchId);
        }
        return count != null && count > 0;
    }

    /** 轮询兜底：只返回尚未过期的通知，按时间升序。 */
    public List<NotificationView> recent(long after, long now, int limit) {
        return jdbc.query("""
                SELECT * FROM game_notification
                 WHERE created_at > ? AND expires_at > ?
                 ORDER BY created_at ASC
                 LIMIT ?
                """, MAPPER, SqlTime.ts(after), SqlTime.ts(now),
                Math.min(50, Math.max(1, limit)));
    }

    public List<NotificationView> latest(long now, int limit) {
        return jdbc.query("""
                SELECT * FROM (
                    SELECT * FROM game_notification
                     WHERE expires_at > ?
                     ORDER BY created_at DESC
                     LIMIT ?
                ) recent ORDER BY created_at ASC
                """, MAPPER, SqlTime.ts(now), Math.min(50, Math.max(1, limit)));
    }
}
