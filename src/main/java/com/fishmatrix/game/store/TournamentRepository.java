package com.fishmatrix.game.store;

import com.fishmatrix.game.model.TournamentPlayerRecord;
import com.fishmatrix.game.model.TournamentRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TournamentRepository {

    private static final RowMapper<TournamentRecord> TOURNAMENT_MAPPER = (rs, rowNum) -> map(rs);
    private static final RowMapper<TournamentPlayerRecord> PLAYER_MAPPER = (rs, rowNum) -> new TournamentPlayerRecord(
            rs.getString("tournament_id"),
            rs.getString("user_id"),
            rs.getString("nickname"),
            rs.getInt("seed"),
            rs.getString("status"),
            SqlTime.millis(rs, "invited_at"),
            SqlTime.nullableMillis(rs, "responded_at"));

    private final JdbcTemplate jdbc;

    public TournamentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TournamentRecord t) {
        jdbc.update("""
                INSERT INTO game_tournament
                    (id, name, game_id, format, bracket_type, status, creator_user_id, creator_nickname,
                     target_players, invite_deadline, current_round, total_rounds, champion_user_id,
                     champion_nickname, created_at, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                t.id(), t.name(), t.gameId(), t.format(), t.bracketType(), t.status(),
                t.creatorUserId(), t.creatorNickname(), t.targetPlayers(),
                SqlTime.ts(t.inviteDeadline()), t.currentRound(), t.totalRounds(),
                t.championUserId(), t.championNickname(), SqlTime.ts(t.createdAt()),
                SqlTime.ts(t.startedAt()), SqlTime.ts(t.finishedAt()));
    }

    public void insertPlayer(TournamentPlayerRecord p) {
        jdbc.update("""
                INSERT INTO game_tournament_player
                    (tournament_id, user_id, nickname, seed, status, invited_at, responded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                p.tournamentId(), p.userId(), p.nickname(), p.seed(), p.status(),
                SqlTime.ts(p.invitedAt()), SqlTime.ts(p.respondedAt()));
    }

    public Optional<TournamentRecord> findById(String id) {
        return jdbc.query("SELECT * FROM game_tournament WHERE id = ?", TOURNAMENT_MAPPER, id)
                .stream().findFirst();
    }

    public List<TournamentRecord> listRecent(int limit) {
        return jdbc.query("""
                SELECT * FROM game_tournament
                 ORDER BY created_at DESC
                 LIMIT ?
                """, TOURNAMENT_MAPPER, Math.min(50, Math.max(1, limit)));
    }

    public List<TournamentRecord> listRunning() {
        return jdbc.query(
                "SELECT * FROM game_tournament WHERE status = 'RUNNING' ORDER BY created_at ASC",
                TOURNAMENT_MAPPER);
    }

    public List<TournamentRecord> listInvitingExpired(long now) {
        return jdbc.query("""
                SELECT * FROM game_tournament
                 WHERE status = 'INVITING' AND invite_deadline <= ?
                 ORDER BY created_at ASC
                """, TOURNAMENT_MAPPER, SqlTime.ts(now));
    }

    public List<TournamentPlayerRecord> listPlayers(String tournamentId) {
        return jdbc.query("""
                SELECT * FROM game_tournament_player
                 WHERE tournament_id = ?
                 ORDER BY seed ASC, invited_at ASC
                """, PLAYER_MAPPER, tournamentId);
    }

    public Optional<TournamentPlayerRecord> findPlayer(String tournamentId, String userId) {
        return jdbc.query("""
                SELECT * FROM game_tournament_player
                 WHERE tournament_id = ? AND user_id = ?
                """, PLAYER_MAPPER, tournamentId, userId).stream().findFirst();
    }

    public int accept(String tournamentId, String userId, String nickname, long now) {
        return jdbc.update("""
                UPDATE game_tournament_player
                   SET status = 'ACCEPTED', nickname = ?, responded_at = ?
                 WHERE tournament_id = ? AND user_id = ? AND status = 'INVITED'
                """, nickname, SqlTime.ts(now), tournamentId, userId);
    }

    public int decline(String tournamentId, String userId, long now) {
        return jdbc.update("""
                UPDATE game_tournament_player
                   SET status = 'DECLINED', responded_at = ?
                 WHERE tournament_id = ? AND user_id = ? AND status = 'INVITED'
                """, SqlTime.ts(now), tournamentId, userId);
    }

    public int expireInvitations(String tournamentId, long now) {
        return jdbc.update("""
                UPDATE game_tournament_player
                   SET status = 'DECLINED', responded_at = ?
                 WHERE tournament_id = ? AND status = 'INVITED'
                """, SqlTime.ts(now), tournamentId);
    }

    public List<TournamentPlayerRecord> acceptedPlayers(String tournamentId) {
        return jdbc.query("""
                SELECT * FROM game_tournament_player
                 WHERE tournament_id = ? AND status = 'ACCEPTED'
                 ORDER BY seed ASC, invited_at ASC
                """, PLAYER_MAPPER, tournamentId);
    }

    public List<TournamentPlayerRecord> allAcceptedOrInvited(String tournamentId) {
        return jdbc.query("""
                SELECT * FROM game_tournament_player
                 WHERE tournament_id = ? AND status IN ('ACCEPTED', 'INVITED')
                 ORDER BY seed ASC, invited_at ASC
                """, PLAYER_MAPPER, tournamentId);
    }

    public List<TournamentRecord> pendingInvitations(String userId, long now) {
        return jdbc.query("""
                SELECT t.* FROM game_tournament t
                  JOIN game_tournament_player p ON p.tournament_id = t.id
                 WHERE p.user_id = ?
                   AND p.status = 'INVITED'
                   AND t.status = 'INVITING'
                   AND t.invite_deadline > ?
                 ORDER BY t.created_at DESC
                """, TOURNAMENT_MAPPER, userId, SqlTime.ts(now));
    }

    public void updateSeed(String tournamentId, String userId, int seed) {
        jdbc.update(
                "UPDATE game_tournament_player SET seed = ? WHERE tournament_id = ? AND user_id = ?",
                seed, tournamentId, userId);
    }

    public int start(String tournamentId, String bracketType, int totalRounds, long now) {
        return jdbc.update("""
                UPDATE game_tournament
                   SET status = 'RUNNING', bracket_type = ?, total_rounds = ?, current_round = 1, started_at = ?
                 WHERE id = ? AND status = 'INVITING'
                """, bracketType, totalRounds, SqlTime.ts(now), tournamentId);
    }

    public void updateCurrentRound(String tournamentId, int round) {
        jdbc.update("UPDATE game_tournament SET current_round = ? WHERE id = ?", round, tournamentId);
    }

    /** 条件推进轮次，避免多个比赛同时结束时重复生成下一轮。 */
    public int advanceRound(String tournamentId, int expectedRound, int nextRound) {
        return jdbc.update("""
                UPDATE game_tournament
                   SET current_round = ?
                 WHERE id = ? AND status = 'RUNNING' AND current_round = ?
                """, nextRound, tournamentId, expectedRound);
    }

    /** 积分赛结束，原子切换到阶梯决赛。 */
    public int startLadder(String tournamentId, int totalRounds, int challengeRound, int expectedRound) {
        return jdbc.update("""
                UPDATE game_tournament
                   SET bracket_type = 'LADDER', total_rounds = ?, current_round = ?
                 WHERE id = ? AND status = 'RUNNING'
                   AND bracket_type = 'LEAGUE' AND current_round = ?
                """, totalRounds, challengeRound, tournamentId, expectedRound);
    }

    public void updateBracketType(String tournamentId, String bracketType, int totalRounds) {
        jdbc.update(
                "UPDATE game_tournament SET bracket_type = ?, total_rounds = ? WHERE id = ?",
                bracketType, totalRounds, tournamentId);
    }

    public void finish(String tournamentId, String championUserId, String championNickname, long now) {
        jdbc.update("""
                UPDATE game_tournament
                   SET status = 'FINISHED', champion_user_id = ?, champion_nickname = ?, finished_at = ?
                 WHERE id = ?
                """, championUserId, championNickname, SqlTime.ts(now), tournamentId);
    }

    public void cancel(String tournamentId, long now) {
        jdbc.update("""
                UPDATE game_tournament
                   SET status = 'CANCELLED', finished_at = ?
                 WHERE id = ? AND status = 'INVITING'
                """, SqlTime.ts(now), tournamentId);
    }

    private static TournamentRecord map(ResultSet rs) throws SQLException {
        return new TournamentRecord(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("game_id"),
                rs.getString("format"),
                rs.getString("bracket_type"),
                rs.getString("status"),
                rs.getString("creator_user_id"),
                rs.getString("creator_nickname"),
                rs.getInt("target_players"),
                SqlTime.millis(rs, "invite_deadline"),
                rs.getInt("current_round"),
                rs.getInt("total_rounds"),
                rs.getString("champion_user_id"),
                rs.getString("champion_nickname"),
                SqlTime.millis(rs, "created_at"),
                SqlTime.nullableMillis(rs, "started_at"),
                SqlTime.nullableMillis(rs, "finished_at"));
    }
}
