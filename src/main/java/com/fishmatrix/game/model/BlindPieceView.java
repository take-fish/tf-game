package com.fishmatrix.game.model;

/**
 * 盲棋公开棋盘格：反面棋只暴露坐标，翻开后才下发颜色与兵种。
 */
public record BlindPieceView(
        int x,
        int y,
        boolean revealed,
        String color,
        String pieceType) {

    public static BlindPieceView hidden(int x, int y) {
        return new BlindPieceView(x, y, false, null, null);
    }

    public static BlindPieceView revealed(int x, int y, BlindColor color, BlindPieceType pieceType) {
        return new BlindPieceView(x, y, true, color.name(), pieceType.name());
    }
}
