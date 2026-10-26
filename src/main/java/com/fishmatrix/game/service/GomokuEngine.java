package com.fishmatrix.game.service;

import com.fishmatrix.game.model.MoveView;
import java.util.List;

/**
 * 15×15 五子棋规则：黑先、无禁手、每局换先。服务端是唯一裁判。
 */
public final class GomokuEngine {

    public static final int SIZE = 15;
    public static final int EMPTY = 0;
    public static final int BLACK = 1;
    public static final int WHITE = 2;

    private static final int[][] DIRECTIONS = {
            {1, 0}, {0, 1}, {1, 1}, {1, -1}
    };

    private GomokuEngine() {
    }

    public static boolean inBounds(int x, int y) {
        return x >= 0 && x < SIZE && y >= 0 && y < SIZE;
    }

    public static int[][] board(List<MoveView> moves, String blackUserId) {
        int[][] board = new int[SIZE][SIZE];
        for (MoveView move : moves) {
            if (!inBounds(move.x(), move.y())) {
                continue;
            }
            board[move.x()][move.y()] = move.userId().equals(blackUserId) ? BLACK : WHITE;
        }
        return board;
    }

    /** 判断最后一手是否形成五连。 */
    public static boolean isWinningMove(int[][] board, int x, int y) {
        if (!inBounds(x, y) || board[x][y] == EMPTY) {
            return false;
        }
        int color = board[x][y];
        for (int[] direction : DIRECTIONS) {
            int count = 1;
            count += countDirection(board, x, y, direction[0], direction[1], color);
            count += countDirection(board, x, y, -direction[0], -direction[1], color);
            if (count >= 5) {
                return true;
            }
        }
        return false;
    }

    public static boolean isFull(int[][] board) {
        for (int[] row : board) {
            for (int value : row) {
                if (value == EMPTY) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int countDirection(int[][] board, int x, int y, int dx, int dy, int color) {
        int count = 0;
        int cx = x + dx;
        int cy = y + dy;
        while (inBounds(cx, cy) && board[cx][cy] == color) {
            count += 1;
            cx += dx;
            cy += dy;
        }
        return count;
    }
}
