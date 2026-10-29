package com.fishmatrix.game.converter;

import com.fishmatrix.game.domain.BlindColor;
import com.fishmatrix.game.domain.BlindPieceType;
import com.fishmatrix.game.domain.MoveAction;
import com.fishmatrix.game.entity.GameMoveEntity;
import com.fishmatrix.game.model.MoveView;
import java.util.ArrayList;
import java.util.List;

/** 棋谱行与公开棋谱视图的转换。 */
public final class MoveConverters {

    private MoveConverters() {
    }

    /** 五子棋落子：只需 move_no 与坐标。 */
    public static GameMoveEntity toEntity(String roundId, int moveNo, String userId,
                                          int x, int y, long now) {
        return toEntity(roundId, moveNo, userId, x, y, MoveAction.PLACE.name(),
                null, null, null, null, null, null, now);
    }

    /** 盲棋动作：REVEAL 记录被翻开的棋，MOVE 记录起点与被吃棋身份。 */
    public static GameMoveEntity toEntity(String roundId, int moveNo, String userId,
                                          int x, int y, String action, Integer fromX, Integer fromY,
                                          String pieceType, String pieceColor,
                                          String capturedPieceType, String capturedPieceColor,
                                          long now) {
        GameMoveEntity entity = new GameMoveEntity();
        entity.setRoundId(roundId);
        entity.setMoveNo(moveNo);
        entity.setUserId(userId);
        entity.setX(x);
        entity.setY(y);
        entity.setAction(action == null ? null : MoveAction.valueOf(action));
        entity.setFromX(fromX);
        entity.setFromY(fromY);
        entity.setPieceType(pieceType == null ? null : BlindPieceType.valueOf(pieceType));
        entity.setPieceColor(pieceColor == null ? null : BlindColor.valueOf(pieceColor));
        entity.setCapturedPieceType(
                capturedPieceType == null ? null : BlindPieceType.valueOf(capturedPieceType));
        entity.setCapturedPieceColor(
                capturedPieceColor == null ? null : BlindColor.valueOf(capturedPieceColor));
        entity.setCreatedAt(SqlTime.toDateTime(now));
        return entity;
    }

    public static MoveView toView(GameMoveEntity entity) {
        if (entity == null) {
            return null;
        }
        return new MoveView(
                entity.getMoveNo() == null ? 0 : entity.getMoveNo(),
                entity.getUserId(),
                entity.getX() == null ? 0 : entity.getX(),
                entity.getY() == null ? 0 : entity.getY(),
                name(entity.getAction()),
                entity.getFromX(),
                entity.getFromY(),
                name(entity.getPieceType()),
                name(entity.getPieceColor()),
                name(entity.getCapturedPieceType()),
                name(entity.getCapturedPieceColor()));
    }

    public static List<MoveView> views(List<GameMoveEntity> entities) {
        List<MoveView> views = new ArrayList<>(entities.size());
        for (GameMoveEntity entity : entities) {
            views.add(toView(entity));
        }
        return views;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
