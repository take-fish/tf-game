package com.fishmatrix.game.converter;

import com.fishmatrix.game.domain.BlindColor;
import com.fishmatrix.game.domain.BlindPieceType;
import com.fishmatrix.game.entity.GameBlindPieceEntity;
import com.fishmatrix.game.service.BlindXiangqiEngine.Piece;
import java.util.ArrayList;
import java.util.List;

/** 盲棋私有棋子与实体之间的转换。 */
public final class BlindPieceConverters {

    private BlindPieceConverters() {
    }

    public static List<GameBlindPieceEntity> toEntities(String roundId, List<Piece> pieces) {
        List<GameBlindPieceEntity> entities = new ArrayList<>(pieces.size());
        for (Piece piece : pieces) {
            GameBlindPieceEntity entity = new GameBlindPieceEntity();
            entity.setRoundId(roundId);
            entity.setPieceId(piece.id());
            entity.setColor(BlindColor.valueOf(piece.color().name()));
            entity.setPieceType(BlindPieceType.valueOf(piece.type().name()));
            entity.setX(piece.x());
            entity.setY(piece.y());
            entity.setRevealed(piece.revealed());
            entity.setAlive(true);
            entities.add(entity);
        }
        return entities;
    }

    public static Piece toPiece(GameBlindPieceEntity entity) {
        if (entity == null) {
            return null;
        }
        return new Piece(
                entity.getPieceId(),
                com.fishmatrix.game.model.BlindColor.valueOf(entity.getColor().name()),
                com.fishmatrix.game.model.BlindPieceType.valueOf(entity.getPieceType().name()),
                entity.getX() == null ? 0 : entity.getX(),
                entity.getY() == null ? 0 : entity.getY(),
                entity.isRevealed());
    }

    public static List<Piece> toPieces(List<GameBlindPieceEntity> entities) {
        List<Piece> pieces = new ArrayList<>(entities.size());
        for (GameBlindPieceEntity entity : entities) {
            pieces.add(toPiece(entity));
        }
        return pieces;
    }
}
