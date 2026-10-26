package com.fishmatrix.game.store;

import com.fishmatrix.game.model.ChatMessageView;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ChatRepository {

    private static final RowMapper<ChatMessageView> MAPPER = (rs, rowNum) -> new ChatMessageView(
            rs.getString("id"),
            rs.getString("tournament_id"),
            rs.getString("match_id"),
            rs.getString("channel"),
            rs.getString("from_user_id"),
            rs.getString("from_nickname"),
            rs.getString("to_user_id"),
            rs.getString("content"),
            SqlTime.millis(rs, "created_at"));

    private final JdbcTemplate jdbc;

    public ChatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ChatMessageView message) {
        jdbc.update("""
                INSERT INTO game_chat_message
                    (id, tournament_id, match_id, channel, from_user_id, from_nickname,
                     to_user_id, content, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                message.id(), message.tournamentId(), message.matchId(), message.channel(),
                message.fromUserId(), message.fromNickname(), message.toUserId(),
                message.content(), SqlTime.ts(message.createdAt()));
    }

    public List<ChatMessageView> history(String tournamentId, String matchId, String channel,
                                         String userA, String userB, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("""
                WHERE tournament_id = ? AND channel = ?
                """);
        args.add(tournamentId);
        args.add(channel);
        if (matchId == null) {
            where.append(" AND match_id IS NULL");
        } else {
            where.append(" AND match_id = ?");
            args.add(matchId);
        }
        if ("PLAYER_DM".equals(channel)) {
            where.append("""
                     AND ((from_user_id = ? AND to_user_id = ?) OR (from_user_id = ? AND to_user_id = ?))
                    """);
            args.add(userA);
            args.add(userB);
            args.add(userB);
            args.add(userA);
        }
        int safeLimit = Math.min(200, Math.max(1, limit));
        args.add(safeLimit);
        String sql = "SELECT * FROM (SELECT * FROM game_chat_message " + where
                + " ORDER BY created_at DESC LIMIT ?) recent ORDER BY created_at ASC";
        return jdbc.query(sql, MAPPER, args.toArray());
    }
}
