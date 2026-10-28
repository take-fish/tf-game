package com.fishmatrix.game.store;

import com.fishmatrix.game.model.BlindColor;
import com.fishmatrix.game.model.BlindPieceType;
import com.fishmatrix.game.service.BlindXiangqiEngine.Piece;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 盲棋服务端私有状态：反面棋的真实颜色与兵种只落在这里，不进入公开视图。
 */
@RequiredArgsConstructor
@Repository
public class BlindXiangqiRepository {

    private static final RowMapper<Piece> PIECE_MAPPER = (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbc;

    public void insertPieces(String roundId, List<Piece> pieces) {
        jdbc.batchUpdate("""
                INSERT INTO game_blind_piece
                    (round_id, piece_id, color, piece_type, x, y, revealed, alive)
                VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)
                """, pieces, pieces.size(), (statement, piece) -> {
            statement.setString(1, roundId);
            statement.setString(2, piece.id());
            statement.setString(3, piece.color().name());
            statement.setString(4, piece.type().name());
            statement.setInt(5, piece.x());
            statement.setInt(6, piece.y());
            statement.setBoolean(7, piece.revealed());
        });
    }

    public List<Piece> listAlivePieces(String roundId) {
        return jdbc.query("""
                SELECT piece_id, color, piece_type, x, y, revealed
                  FROM game_blind_piece
                 WHERE round_id = ? AND alive = TRUE
                 ORDER BY piece_id
                """, PIECE_MAPPER, roundId);
    }

    public int reveal(String roundId, String pieceId) {
        return jdbc.update("""
                UPDATE game_blind_piece
                   SET revealed = TRUE
                 WHERE round_id = ? AND piece_id = ? AND alive = TRUE AND revealed = FALSE
                """, roundId, pieceId);
    }

    public int move(String roundId, String pieceId, int x, int y) {
        return jdbc.update("""
                UPDATE game_blind_piece
                   SET x = ?, y = ?
                 WHERE round_id = ? AND piece_id = ? AND alive = TRUE AND revealed = TRUE
                """, x, y, roundId, pieceId);
    }

    public int capture(String roundId, String pieceId) {
        return jdbc.update("""
                UPDATE game_blind_piece
                   SET alive = FALSE
                 WHERE round_id = ? AND piece_id = ? AND alive = TRUE
                """, roundId, pieceId);
    }

    private static Piece map(ResultSet rs) throws SQLException {
        return new Piece(
                rs.getString("piece_id"),
                BlindColor.valueOf(rs.getString("color")),
                BlindPieceType.valueOf(rs.getString("piece_type")),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getBoolean("revealed"));
    }
}
