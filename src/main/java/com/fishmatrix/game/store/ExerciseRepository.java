package com.fishmatrix.game.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 集群巡检对抗的房间、成员与战绩持久化。
 * 实时状态每秒写一次 state_json，服务重启后可以恢复仍在进行的房间。
 */
@Repository
public class ExerciseRepository {

    private static final RowMapper<RoomRow> ROOM_MAPPER = (rs, rowNum) -> mapRoom(rs);
    private static final RowMapper<MemberRow> MEMBER_MAPPER = (rs, rowNum) -> mapMember(rs);
    private static final RowMapper<ResultRow> RESULT_MAPPER = (rs, rowNum) -> mapResult(rs);

    private final JdbcTemplate jdbc;

    public ExerciseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertRoom(RoomRow room) {
        jdbc.update("""
                INSERT INTO game_exercise_room
                    (id, room_code, mode, source, status, match_id, owner_user_id,
                     max_players, duration_seconds, state_json, state_version,
                     winner_user_id, winner_team, finish_reason,
                     created_at, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                room.id(), room.roomCode(), room.mode(), room.source(), room.status(),
                room.matchId(), room.ownerUserId(), room.maxPlayers(), room.durationSeconds(),
                room.stateJson(), room.stateVersion(), room.winnerUserId(), room.winnerTeam(),
                room.finishReason(), SqlTime.ts(room.createdAt()), SqlTime.ts(room.startedAt()),
                SqlTime.ts(room.finishedAt()));
    }

    public void updateRoomState(String roomId, String stateJson, int stateVersion, String status) {
        jdbc.update("""
                UPDATE game_exercise_room
                   SET state_json = ?, state_version = ?, status = ?
                 WHERE id = ?
                """, stateJson, stateVersion, status, roomId);
    }

    public void finishRoom(String roomId, String winnerUserId, String winnerTeam,
                           String reason, String stateJson, int stateVersion, long now) {
        jdbc.update("""
                UPDATE game_exercise_room
                   SET status = 'FINISHED', winner_user_id = ?, winner_team = ?,
                       finish_reason = ?, state_json = ?, state_version = ?, finished_at = ?
                 WHERE id = ?
                """, winnerUserId, winnerTeam, reason, stateJson, stateVersion,
                SqlTime.ts(now), roomId);
    }

    public void cancelRoom(String roomId, String reason, long now) {
        jdbc.update("""
                UPDATE game_exercise_room
                   SET status = 'CANCELLED', finish_reason = ?, finished_at = ?
                 WHERE id = ? AND status = 'WAITING'
                """, reason, SqlTime.ts(now), roomId);
    }

    public void insertMember(MemberRow member) {
        jdbc.update("""
                INSERT INTO game_exercise_member
                    (room_id, user_id, nickname, team, ready, bot,
                     score, kills, deaths, assists, joined_at, left_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                member.roomId(), member.userId(), member.nickname(), member.team(),
                member.ready(), member.bot(), member.score(), member.kills(),
                member.deaths(), member.assists(), SqlTime.ts(member.joinedAt()),
                SqlTime.ts(member.leftAt()));
    }

    public void updateMemberReady(String roomId, String userId, boolean ready) {
        jdbc.update("""
                UPDATE game_exercise_member SET ready = ?
                 WHERE room_id = ? AND user_id = ?
                """, ready, roomId, userId);
    }

    public void updateMemberLeft(String roomId, String userId, long now) {
        jdbc.update("""
                UPDATE game_exercise_member SET left_at = ?
                 WHERE room_id = ? AND user_id = ? AND left_at IS NULL
                """, SqlTime.ts(now), roomId, userId);
    }

    public void updateMemberStats(String roomId, String userId, int score, int kills,
                                  int deaths, int assists) {
        jdbc.update("""
                UPDATE game_exercise_member
                   SET score = ?, kills = ?, deaths = ?, assists = ?
                 WHERE room_id = ? AND user_id = ?
                """, score, kills, deaths, assists, roomId, userId);
    }

    public void insertResult(ResultRow result) {
        jdbc.update("""
                INSERT INTO game_exercise_result
                    (id, room_id, mode, user_id, nickname, team, rank_no,
                     score, kills, deaths, assists, winner, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                result.id(), result.roomId(), result.mode(), result.userId(),
                result.nickname(), result.team(), result.rankNo(), result.score(),
                result.kills(), result.deaths(), result.assists(), result.winner(),
                SqlTime.ts(result.createdAt()));
    }

    public List<ResultRow> listRecentResults(int limit) {
        return jdbc.query("""
                SELECT * FROM game_exercise_result
                 WHERE mode <> 'TRAINING'
                 ORDER BY created_at DESC, rank_no ASC
                 LIMIT ?
                """, RESULT_MAPPER, limit);
    }

    public List<RoomRow> listRunningRooms() {
        return jdbc.query("""
                SELECT * FROM game_exercise_room
                 WHERE status = 'RUNNING' AND state_json IS NOT NULL
                 ORDER BY started_at ASC
                """, ROOM_MAPPER);
    }

    public List<MemberRow> listMembers(String roomId) {
        return jdbc.query("""
                SELECT * FROM game_exercise_member
                 WHERE room_id = ?
                 ORDER BY joined_at ASC
                """, MEMBER_MAPPER, roomId);
    }

    private static RoomRow mapRoom(ResultSet rs) throws SQLException {
        return new RoomRow(
                rs.getString("id"),
                rs.getString("room_code"),
                rs.getString("mode"),
                rs.getString("source"),
                rs.getString("status"),
                rs.getString("match_id"),
                rs.getString("owner_user_id"),
                rs.getInt("max_players"),
                rs.getInt("duration_seconds"),
                rs.getString("state_json"),
                rs.getInt("state_version"),
                rs.getString("winner_user_id"),
                rs.getString("winner_team"),
                rs.getString("finish_reason"),
                SqlTime.millis(rs, "created_at"),
                SqlTime.nullableMillis(rs, "started_at"),
                SqlTime.nullableMillis(rs, "finished_at"));
    }

    private static MemberRow mapMember(ResultSet rs) throws SQLException {
        return new MemberRow(
                rs.getString("room_id"),
                rs.getString("user_id"),
                rs.getString("nickname"),
                rs.getString("team"),
                rs.getBoolean("ready"),
                rs.getBoolean("bot"),
                rs.getInt("score"),
                rs.getInt("kills"),
                rs.getInt("deaths"),
                rs.getInt("assists"),
                SqlTime.millis(rs, "joined_at"),
                SqlTime.nullableMillis(rs, "left_at"));
    }

    private static ResultRow mapResult(ResultSet rs) throws SQLException {
        return new ResultRow(
                rs.getString("id"),
                rs.getString("room_id"),
                rs.getString("mode"),
                rs.getString("user_id"),
                rs.getString("nickname"),
                rs.getString("team"),
                rs.getInt("rank_no"),
                rs.getInt("score"),
                rs.getInt("kills"),
                rs.getInt("deaths"),
                rs.getInt("assists"),
                rs.getBoolean("winner"),
                SqlTime.millis(rs, "created_at"));
    }

    public record RoomRow(
            String id, String roomCode, String mode, String source, String status,
            String matchId, String ownerUserId, int maxPlayers, int durationSeconds,
            String stateJson, int stateVersion, String winnerUserId, String winnerTeam,
            String finishReason, long createdAt, Long startedAt, Long finishedAt) {
    }

    public record MemberRow(
            String roomId, String userId, String nickname, String team,
            boolean ready, boolean bot, int score, int kills, int deaths, int assists,
            long joinedAt, Long leftAt) {
    }

    public record ResultRow(
            String id, String roomId, String mode, String userId, String nickname,
            String team, int rankNo, int score, int kills, int deaths, int assists,
            boolean winner, long createdAt) {
    }
}
