package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fishmatrix.game.model.MoveView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GomokuEngineTest {

    private static final String BLACK = "u1";
    private static final String WHITE = "u2";

    @Test
    void detectsHorizontalVerticalAndDiagonalWins() {
        assertThat(winningMove(List.of(
                move(1, BLACK, 3, 3), move(2, BLACK, 4, 3), move(3, BLACK, 5, 3),
                move(4, BLACK, 6, 3), move(5, BLACK, 7, 3)), 7, 3)).isTrue();

        assertThat(winningMove(List.of(
                move(1, BLACK, 0, 0), move(2, BLACK, 0, 1), move(3, BLACK, 0, 2),
                move(4, BLACK, 0, 3), move(5, BLACK, 0, 4)), 0, 4)).isTrue();

        assertThat(winningMove(List.of(
                move(1, BLACK, 2, 2), move(2, BLACK, 3, 3), move(3, BLACK, 4, 4),
                move(4, BLACK, 5, 5), move(5, BLACK, 6, 6)), 6, 6)).isTrue();

        assertThat(winningMove(List.of(
                move(1, BLACK, 2, 8), move(2, BLACK, 3, 7), move(3, BLACK, 4, 6),
                move(4, BLACK, 5, 5), move(5, BLACK, 6, 4)), 6, 4)).isTrue();
    }

    @Test
    void fourInRowIsNotAWinningMove() {
        assertThat(winningMove(List.of(
                move(1, BLACK, 3, 3), move(2, BLACK, 4, 3),
                move(3, BLACK, 5, 3), move(4, BLACK, 6, 3)), 6, 3)).isFalse();
    }

    @Test
    void breaksFiveWhenOpponentStoneGapsLine() {
        List<MoveView> moves = List.of(
                move(1, BLACK, 3, 3), move(2, WHITE, 4, 3), move(3, BLACK, 5, 3),
                move(4, BLACK, 6, 3), move(5, BLACK, 7, 3), move(6, BLACK, 8, 3));
        assertThat(winningMove(moves, 8, 3)).isFalse();
    }

    @Test
    void mapsMovesToBlackAndWhiteColors() {
        int[][] board = GomokuEngine.board(List.of(
                move(1, BLACK, 0, 0), move(2, WHITE, 1, 1)), BLACK);
        assertThat(board[0][0]).isEqualTo(GomokuEngine.BLACK);
        assertThat(board[1][1]).isEqualTo(GomokuEngine.WHITE);
    }

    @Test
    void detectsFullBoardAndBounds() {
        List<MoveView> moves = new ArrayList<>();
        int number = 1;
        for (int x = 0; x < GomokuEngine.SIZE; x += 1) {
            for (int y = 0; y < GomokuEngine.SIZE; y += 1) {
                moves.add(move(number++, x % 2 == 0 ? BLACK : WHITE, x, y));
            }
        }
        assertThat(GomokuEngine.isFull(GomokuEngine.board(moves, BLACK))).isTrue();
        assertThat(GomokuEngine.inBounds(0, 0)).isTrue();
        assertThat(GomokuEngine.inBounds(-1, 0)).isFalse();
        assertThat(GomokuEngine.inBounds(15, 0)).isFalse();
    }

    private static boolean winningMove(List<MoveView> moves, int x, int y) {
        int[][] board = GomokuEngine.board(moves, BLACK);
        return GomokuEngine.isWinningMove(board, x, y);
    }

    private static MoveView move(int number, String user, int x, int y) {
        return new MoveView(number, user, x, y);
    }
}
