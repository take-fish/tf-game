package com.fishmatrix.game.store;

import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.MoveView;
import com.fishmatrix.game.model.RoundRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@RequiredArgsConstructor
@Repository
public class MatchRepository {

    private static final RowMapper<MatchRecord> MATCH_MAPPER = (rs, rowNum) -> mapMatch(rs);
    private static final RowMapper<RoundRecord> ROUND_MAPPER = (rs, rowNum) -> mapRound(rs);
    private static final RowMapper<MoveView> MOVE_MAPPER = (rs, rowNum) -> new MoveView(
            rs.getInt("move_no"), rs.getString("user_id"), rs.getInt("x"), rs.getInt("y"),
            rs.getString("action"),
            SqlTime.nullableInt(rs, "from_x"), SqlTime.nullableInt(rs, "from_y"),
            rs.getString("piece_type"), rs.getString("piece_color"),
            rs.getString("captured_piece_type"), rs.getString("captured_piece_color"));

    private final JdbcTemplate jdbc;

    public void insertMatch(MatchRecord match) {
        jdbc.update("""
                INSERT INTO game_match
                    (id, tournament_id, round_no, slot_no, stage, status,
                     player1_user_id, player1_nickname, player2_user_id, player2_nickname,
                     player1_score, player2_score, winner_user_id, next_match_id,
                     first_game_id, game_chooser_user_id,
                     created_at, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                match.id(), match.tournamentId(), match.roundNo(), match.slotNo(), match.stage(),
                match.status(), match.player1UserId(), match.player1Nickname(),
                match.player2UserId(), match.player2Nickname(), match.player1Score(),
                match.player2Score(), match.winnerUserId(), match.nextMatchId(),
                match.firstGameId(), match.gameChooserUserId(),
                SqlTime.ts(match.createdAt()), SqlTime.ts(match.startedAt()),
                SqlTime.ts(match.finishedAt()));
    }

    public Optional<MatchRecord> findById(String id) {
        return jdbc.query("SELECT * FROM game_match WHERE id = ?", MATCH_MAPPER, id)
                .stream().findFirst();
    }

    public List<MatchRecord> listByTournament(String tournamentId) {
        return jdbc.query("""
                SELECT * FROM game_match
                 WHERE tournament_id = ?
                 ORDER BY round_no ASC, slot_no ASC
                """, MATCH_MAPPER, tournamentId);
    }

    public List<MatchRecord> listByStage(String tournamentId, String stage) {
        return jdbc.query("""
                SELECT * FROM game_match
                 WHERE tournament_id = ? AND stage = ?
                 ORDER BY round_no ASC, slot_no ASC
                """, MATCH_MAPPER, tournamentId, stage);
    }

    public List<MatchRecord> listReadyOrRunningByUser(String userId) {
        return jdbc.query("""
                SELECT * FROM game_match
                 WHERE status IN ('READY', 'RUNNING', 'REST')
                   AND (player1_user_id = ? OR player2_user_id = ?)
                 ORDER BY created_at DESC
                """, MATCH_MAPPER, userId, userId);
    }

    /** 积分赛单轮全部结束后，把下一轮从 PENDING 激活为 READY。 */
    public int activateRound(String tournamentId, String stage, int roundNo) {
        return jdbc.update("""
                UPDATE game_match
                   SET status = 'READY'
                 WHERE tournament_id = ? AND stage = ? AND round_no = ? AND status = 'PENDING'
                """, tournamentId, stage, roundNo);
    }

    public int countRoundMatches(String tournamentId, String stage, int roundNo) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM game_match
                 WHERE tournament_id = ? AND stage = ? AND round_no = ?
                """, Integer.class, tournamentId, stage, roundNo);
        return count == null ? 0 : count;
    }

    public int countRoundFinished(String tournamentId, String stage, int roundNo) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM game_match
                 WHERE tournament_id = ? AND stage = ? AND round_no = ?
                   AND status IN ('FINISHED', 'BYE')
                """, Integer.class, tournamentId, stage, roundNo);
        return count == null ? 0 : count;
    }

    /** 休息时间已到的小局，由定时任务自动开始下一局。 */
    public List<RoundRecord> listDueRest(long now) {
        return jdbc.query("""
                SELECT * FROM game_round
                 WHERE status = 'REST' AND rest_until <= ?
                 ORDER BY rest_until ASC
                """, ROUND_MAPPER, SqlTime.ts(now));
    }

    /** 正在对局的小局，用于每步 30 秒超时判定；竞技场小局没有轮次，不参与落子超时。 */
    public List<RoundRecord> listRunningRounds() {
        return jdbc.query("""
                SELECT * FROM game_round
                 WHERE status = 'RUNNING' AND turn_user_id IS NOT NULL
                 ORDER BY last_move_at ASC
                """, ROUND_MAPPER);
    }

    public void updateStatus(String matchId, String status) {
        jdbc.update("UPDATE game_match SET status = ? WHERE id = ?", status, matchId);
    }

    /** 首局剧本只允许在开赛前修改；条件更新防止比赛开始后被覆盖。 */
    public int updateFirstGame(String matchId, String gameId) {
        return jdbc.update("""
                UPDATE game_match
                   SET first_game_id = ?
                 WHERE id = ? AND status IN ('PENDING', 'READY')
                """, gameId, matchId);
    }

    /** 服务端补抽首局剧本：仅当尚未设置时写入，避免覆盖选手已选结果。 */
    public int initFirstGame(String matchId, String gameId, String chooserUserId) {
        return jdbc.update("""
                UPDATE game_match
                   SET first_game_id = COALESCE(first_game_id, ?),
                       game_chooser_user_id = COALESCE(game_chooser_user_id, ?)
                 WHERE id = ? AND status IN ('PENDING', 'READY')
                """, gameId, chooserUserId, matchId);
    }

    public int start(String matchId, long now) {
        return jdbc.update("""
                UPDATE game_match SET status = 'RUNNING', started_at = ?
                 WHERE id = ? AND status IN ('READY', 'REST')
                """, SqlTime.ts(now), matchId);
    }

    public void finish(String matchId, String winnerUserId, int player1Score, int player2Score, long now) {
        jdbc.update("""
                UPDATE game_match
                   SET status = 'FINISHED', winner_user_id = ?, player1_score = ?,
                       player2_score = ?, finished_at = ?
                 WHERE id = ?
                """, winnerUserId, player1Score, player2Score, SqlTime.ts(now), matchId);
    }

    /** 条件结束比赛：只有 RUNNING 才能被结算，避免超时/认输并发重复加分。 */
    public int finishIfRunning(String matchId, String winnerUserId,
                               int player1Score, int player2Score, long now) {
        return jdbc.update("""
                UPDATE game_match
                   SET status = 'FINISHED', winner_user_id = ?, player1_score = ?,
                       player2_score = ?, finished_at = ?
                 WHERE id = ? AND status = 'RUNNING'
                """, winnerUserId, player1Score, player2Score, SqlTime.ts(now), matchId);
    }

    /** 未开赛或进行中的比赛都可因认输结束。 */
    public int finishActive(String matchId, String winnerUserId,
                            int player1Score, int player2Score, long now) {
        return jdbc.update("""
                UPDATE game_match
                   SET status = 'FINISHED', winner_user_id = ?, player1_score = ?,
                       player2_score = ?, finished_at = ?
                 WHERE id = ? AND status IN ('READY', 'RUNNING')
                """, winnerUserId, player1Score, player2Score, SqlTime.ts(now), matchId);
    }

    public int finishRoundIfRunning(String roundId, String winnerUserId, long now) {
        return jdbc.update("""
                UPDATE game_round
                   SET status = 'FINISHED', winner_user_id = ?, rest_until = NULL, finished_at = ?
                 WHERE id = ? AND status = 'RUNNING'
                """, winnerUserId, SqlTime.ts(now), roundId);
    }

    public int restAfterGame(String roundId, String winnerUserId, long restUntil, long now) {
        return jdbc.update("""
                UPDATE game_round
                   SET status = 'REST', winner_user_id = ?, rest_until = ?
                 WHERE id = ? AND status = 'RUNNING'
                """, winnerUserId, SqlTime.ts(restUntil), roundId);
    }

    public void updateScore(String matchId, int player1Score, int player2Score) {
        jdbc.update(
                "UPDATE game_match SET player1_score = ?, player2_score = ? WHERE id = ?",
                player1Score, player2Score, matchId);
    }

    /** 把上一轮胜者填入下一轮比赛的 player1 / player2 位置。 */
    public void fillNextSlot(String nextMatchId, String userId, String nickname, boolean slot1) {
        if (slot1) {
            jdbc.update("""
                    UPDATE game_match
                       SET player1_user_id = ?, player1_nickname = ?
                     WHERE id = ?
                    """, userId, nickname, nextMatchId);
        } else {
            jdbc.update("""
                    UPDATE game_match
                       SET player2_user_id = ?, player2_nickname = ?
                     WHERE id = ?
                    """, userId, nickname, nextMatchId);
        }
    }

    public void insertRound(RoundRecord round) {
        jdbc.update("""
                INSERT INTO game_round
                    (id, match_id, game_id, game_no, status, black_user_id, white_user_id,
                     attacker_user_id, turn_user_id, winner_user_id, move_count, last_move_at,
                     rest_until, ready_user_ids, state_json, state_version, started_at, ends_at,
                     finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                round.id(), round.matchId(), round.gameId(), round.gameNo(), round.status(),
                round.blackUserId(), round.whiteUserId(), round.attackerUserId(),
                round.turnUserId(), round.winnerUserId(), round.moveCount(),
                SqlTime.ts(round.lastMoveAt()), SqlTime.ts(round.restUntil()),
                round.readyUserIds(), round.stateJson(), round.stateVersion(),
                SqlTime.ts(round.startedAt()), SqlTime.ts(round.endsAt()),
                SqlTime.ts(round.finishedAt()));
    }

    /** 竞技场每秒保存一次权威状态；只允许 RUNNING 小局写入。 */
    public void updateArenaState(String roundId, String stateJson, int stateVersion, int actionCount) {
        jdbc.update("""
                UPDATE game_round
                   SET state_json = ?, state_version = ?,
                       move_count = GREATEST(move_count, ?)
                 WHERE id = ? AND status = 'RUNNING'
                """, stateJson, stateVersion, actionCount, roundId);
    }

    /** 服务重启后恢复仍在对局的竞技场小局。 */
    public List<RoundRecord> listRunningArenaRounds(String gomokuGameId) {
        return jdbc.query("""
                SELECT * FROM game_round
                 WHERE status = 'RUNNING' AND game_id <> ?
                 ORDER BY started_at ASC
                """, ROUND_MAPPER, gomokuGameId);
    }

    public Optional<RoundRecord> findRound(String roundId) {
        return jdbc.query("SELECT * FROM game_round WHERE id = ?", ROUND_MAPPER, roundId)
                .stream().findFirst();
    }

    public Optional<RoundRecord> latestRound(String matchId) {
        return jdbc.query("""
                SELECT * FROM game_round
                 WHERE match_id = ?
                 ORDER BY game_no DESC
                 LIMIT 1
                """, ROUND_MAPPER, matchId).stream().findFirst();
    }

    public List<RoundRecord> listRounds(String matchId) {
        return jdbc.query("""
                SELECT * FROM game_round WHERE match_id = ? ORDER BY game_no ASC
                """, ROUND_MAPPER, matchId);
    }

    public List<MoveView> listMoves(String roundId) {
        return jdbc.query("""
                SELECT move_no, user_id, x, y, action, from_x, from_y,
                       piece_type, piece_color, captured_piece_type, captured_piece_color
                  FROM game_move
                 WHERE round_id = ? ORDER BY move_no ASC
                """, MOVE_MAPPER, roundId);
    }

    public void insertMove(String roundId, int moveNo, String userId, int x, int y, long now) {
        insertMove(roundId, moveNo, userId, x, y, "PLACE",
                null, null, null, null, null, null, now);
    }

    /** 盲棋动作：REVEAL 记录被翻开的棋，MOVE 记录起点与被吃棋身份。 */
    public void insertMove(String roundId, int moveNo, String userId, int x, int y,
                           String action, Integer fromX, Integer fromY,
                           String pieceType, String pieceColor,
                           String capturedPieceType, String capturedPieceColor, long now) {
        jdbc.update("""
                INSERT INTO game_move
                    (round_id, move_no, user_id, x, y, action, from_x, from_y,
                     piece_type, piece_color, captured_piece_type, captured_piece_color, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, roundId, moveNo, userId, x, y, action, fromX, fromY,
                pieceType, pieceColor, capturedPieceType, capturedPieceColor, SqlTime.ts(now));
    }

    /** 乐观更新：只有 move_count 与预期一致时才切换回合，避免并发落子覆盖。 */
    public int updateTurnAndCount(String roundId, int expectedCount, int nextCount,
                                  String nextTurnUserId, long now) {
        return jdbc.update("""
                UPDATE game_round
                   SET turn_user_id = ?, move_count = ?, last_move_at = ?
                 WHERE id = ? AND move_count = ? AND status = 'RUNNING'
                """, nextTurnUserId, nextCount, SqlTime.ts(now), roundId, expectedCount);
    }

    public void updateRoundStatus(String roundId, String status, String winnerUserId,
                                  Long restUntil, Long finishedAt) {
        jdbc.update("""
                UPDATE game_round
                   SET status = ?, winner_user_id = ?, rest_until = ?, finished_at = ?
                 WHERE id = ?
                """, status, winnerUserId, SqlTime.ts(restUntil), SqlTime.ts(finishedAt), roundId);
    }

    public void updateReadyUserIds(String roundId, String readyUserIds) {
        jdbc.update("UPDATE game_round SET ready_user_ids = ? WHERE id = ?", readyUserIds, roundId);
    }

    /** 条件更新：休息中的小局只允许被一个调用方推进到下一局。 */
    public int finishRestRound(String roundId, long now) {
        return jdbc.update("""
                UPDATE game_round
                   SET status = 'FINISHED', rest_until = NULL, finished_at = ?
                 WHERE id = ? AND status = 'REST'
                """, SqlTime.ts(now), roundId);
    }

    public List<RoundRecord> listByStatus(String status) {
        return jdbc.query(
                "SELECT * FROM game_round WHERE status = ? ORDER BY started_at ASC",
                ROUND_MAPPER, status);
    }

    public int countLeagueMatches(String tournamentId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM game_match
                 WHERE tournament_id = ? AND stage = 'LEAGUE'
                """, Integer.class, tournamentId);
        return count == null ? 0 : count;
    }

    public int countLeagueFinished(String tournamentId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM game_match
                 WHERE tournament_id = ? AND stage = 'LEAGUE'
                   AND status IN ('FINISHED', 'BYE')
                """, Integer.class, tournamentId);
        return count == null ? 0 : count;
    }

    private static MatchRecord mapMatch(ResultSet rs) throws SQLException {
        return new MatchRecord(
                rs.getString("id"),
                rs.getString("tournament_id"),
                rs.getInt("round_no"),
                rs.getInt("slot_no"),
                rs.getString("stage"),
                rs.getString("status"),
                rs.getString("player1_user_id"),
                rs.getString("player1_nickname"),
                rs.getString("player2_user_id"),
                rs.getString("player2_nickname"),
                rs.getInt("player1_score"),
                rs.getInt("player2_score"),
                rs.getString("winner_user_id"),
                rs.getString("next_match_id"),
                SqlTime.millis(rs, "created_at"),
                SqlTime.nullableMillis(rs, "started_at"),
                SqlTime.nullableMillis(rs, "finished_at"),
                rs.getString("first_game_id"),
                rs.getString("game_chooser_user_id"));
    }

    private static RoundRecord mapRound(ResultSet rs) throws SQLException {
        return new RoundRecord(
                rs.getString("id"),
                rs.getString("match_id"),
                rs.getString("game_id"),
                rs.getInt("game_no"),
                rs.getString("status"),
                rs.getString("black_user_id"),
                rs.getString("white_user_id"),
                rs.getString("attacker_user_id"),
                rs.getString("turn_user_id"),
                rs.getString("winner_user_id"),
                rs.getInt("move_count"),
                SqlTime.millis(rs, "last_move_at"),
                SqlTime.nullableMillis(rs, "rest_until"),
                rs.getString("ready_user_ids"),
                rs.getString("state_json"),
                rs.getInt("state_version"),
                SqlTime.millis(rs, "started_at"),
                SqlTime.nullableMillis(rs, "ends_at"),
                SqlTime.nullableMillis(rs, "finished_at"));
    }
}
