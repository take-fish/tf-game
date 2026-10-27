package com.fishmatrix.game.model;

/**
 * 一步公开棋谱。五子棋只使用 x/y；盲棋额外记录动作、起点与被吃棋子身份。
 */
public record MoveView(
        int moveNo,
        String userId,
        int x,
        int y,
        String action,
        Integer fromX,
        Integer fromY,
        String pieceType,
        String pieceColor,
        String capturedPieceType,
        String capturedPieceColor) {

    /** 五子棋落子兼容构造器。 */
    public MoveView(int moveNo, String userId, int x, int y) {
        this(moveNo, userId, x, y, "PLACE", null, null, null, null, null, null);
    }
}
