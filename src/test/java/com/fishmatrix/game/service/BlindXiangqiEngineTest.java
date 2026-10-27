package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fishmatrix.game.model.BlindColor;
import com.fishmatrix.game.model.BlindLayout;
import com.fishmatrix.game.model.BlindPieceType;
import com.fishmatrix.game.service.BlindXiangqiEngine.Piece;
import com.fishmatrix.game.service.BlindXiangqiEngine.Placement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BlindXiangqiEngineTest {

    @Test
    void standardShuffleUsesAllStandardCellsAndFullArmy() {
        List<Placement> placements = BlindXiangqiEngine.generateLayout(BlindLayout.STANDARD_SHUFFLE);

        assertThat(placements).hasSize(32);
        List<String> cells = placements.stream()
                .map(piece -> piece.x() + "," + piece.y())
                .toList();
        assertThat(cells).doesNotHaveDuplicates().hasSize(32);
        assertThat(cells).allSatisfy(cell -> assertThat(standardCells()).contains(cell));
        assertArmyComposition(placements);
    }

    @Test
    void fullRandomSpreadsPiecesAcrossWholeBoard() {
        List<Placement> placements = BlindXiangqiEngine.generateLayout(BlindLayout.FULL_RANDOM);

        assertThat(placements).hasSize(32);
        assertThat(placements.stream().map(piece -> piece.x() + "," + piece.y()).toList())
                .doesNotHaveDuplicates();
        assertThat(placements).allSatisfy(piece -> {
            assertThat(piece.x()).isBetween(0, 8);
            assertThat(piece.y()).isBetween(0, 9);
        });
        assertArmyComposition(placements);
    }

    @Test
    void halfRandomKeepsEachColorInOwnHalf() {
        List<Placement> placements = BlindXiangqiEngine.generateLayout(BlindLayout.HALF_RANDOM);

        assertThat(placements).hasSize(32);
        assertThat(placements.stream().map(piece -> piece.x() + "," + piece.y()).toList())
                .doesNotHaveDuplicates();
        assertThat(placements).filteredOn(piece -> piece.color() == BlindColor.RED)
                .hasSize(16)
                .allSatisfy(piece -> assertThat(piece.y()).isBetween(5, 9));
        assertThat(placements).filteredOn(piece -> piece.color() == BlindColor.BLACK)
                .hasSize(16)
                .allSatisfy(piece -> assertThat(piece.y()).isBetween(0, 4));
        assertArmyComposition(placements);
    }

    @Test
    void hiddenPiecesBlockChariotButCanBeBlindCaptured() {
        List<Piece> pieces = List.of(
                revealed("r1", BlindColor.RED, BlindPieceType.CHARIOT, 0, 9),
                hidden("b1", BlindColor.BLACK, BlindPieceType.SOLDIER, 0, 5));

        assertThat(BlindXiangqiEngine.canMove(pieces, BlindColor.RED, 0, 9, 0, 0)).isFalse();
        assertThat(BlindXiangqiEngine.canMove(pieces, BlindColor.RED, 0, 9, 0, 6)).isTrue();
        assertThat(BlindXiangqiEngine.canMove(pieces, BlindColor.RED, 0, 9, 0, 5)).isTrue();
    }

    @Test
    void cannonNeedsExactlyOneScreenToCapture() {
        List<Piece> oneScreen = List.of(
                revealed("r1", BlindColor.RED, BlindPieceType.CANNON, 0, 9),
                hidden("b1", BlindColor.BLACK, BlindPieceType.SOLDIER, 0, 7),
                hidden("r2", BlindColor.RED, BlindPieceType.SOLDIER, 0, 5));
        assertThat(BlindXiangqiEngine.canMove(oneScreen, BlindColor.RED, 0, 9, 0, 8)).isTrue();
        assertThat(BlindXiangqiEngine.canMove(oneScreen, BlindColor.RED, 0, 9, 0, 5)).isTrue();

        List<Piece> noScreen = List.of(
                revealed("r1", BlindColor.RED, BlindPieceType.CANNON, 0, 9),
                revealed("b1", BlindColor.BLACK, BlindPieceType.SOLDIER, 0, 5));
        assertThat(BlindXiangqiEngine.canMove(noScreen, BlindColor.RED, 0, 9, 0, 5)).isFalse();

        List<Piece> twoScreens = List.of(
                revealed("r1", BlindColor.RED, BlindPieceType.CANNON, 0, 9),
                hidden("b1", BlindColor.BLACK, BlindPieceType.SOLDIER, 0, 7),
                hidden("b2", BlindColor.BLACK, BlindPieceType.SOLDIER, 0, 6),
                hidden("b3", BlindColor.BLACK, BlindPieceType.SOLDIER, 0, 5));
        assertThat(BlindXiangqiEngine.canMove(twoScreens, BlindColor.RED, 0, 9, 0, 5)).isFalse();
    }

    @Test
    void horseLegIsBlockedByHiddenPiece() {
        List<Piece> blocked = List.of(
                revealed("r1", BlindColor.RED, BlindPieceType.HORSE, 4, 9),
                hidden("b1", BlindColor.BLACK, BlindPieceType.SOLDIER, 4, 8));
        assertThat(BlindXiangqiEngine.canMove(blocked, BlindColor.RED, 4, 9, 5, 7)).isFalse();

        List<Piece> clear = List.of(
                revealed("r1", BlindColor.RED, BlindPieceType.HORSE, 4, 9));
        assertThat(BlindXiangqiEngine.canMove(clear, BlindColor.RED, 4, 9, 5, 7)).isTrue();
    }

    @Test
    void elephantCannotJumpEyeButMayCrossRiver() {
        List<Piece> blockedEye = List.of(
                revealed("r1", BlindColor.RED, BlindPieceType.ELEPHANT, 2, 9),
                hidden("b1", BlindColor.BLACK, BlindPieceType.SOLDIER, 3, 8));
        assertThat(BlindXiangqiEngine.canMove(blockedEye, BlindColor.RED, 2, 9, 4, 7)).isFalse();

        List<Piece> clear = List.of(
                revealed("r1", BlindColor.RED, BlindPieceType.ELEPHANT, 2, 9));
        assertThat(BlindXiangqiEngine.canMove(clear, BlindColor.RED, 2, 9, 4, 7)).isTrue();
        assertThat(BlindXiangqiEngine.canMove(clear, BlindColor.RED, 2, 9, 0, 7)).isTrue();

        List<Piece> riverEdge = List.of(
                revealed("r1", BlindColor.RED, BlindPieceType.ELEPHANT, 2, 5));
        assertThat(BlindXiangqiEngine.canMove(riverEdge, BlindColor.RED, 2, 5, 4, 3)).isTrue();
        assertThat(BlindXiangqiEngine.canMove(riverEdge, BlindColor.RED, 2, 5, 0, 3)).isTrue();
    }

    @Test
    void generalAndAdvisorMoveByPatternAnywhere() {
        List<Piece> general = List.of(
                revealed("g1", BlindColor.RED, BlindPieceType.GENERAL, 4, 7));
        assertThat(BlindXiangqiEngine.canMove(general, BlindColor.RED, 4, 7, 4, 6)).isTrue();
        assertThat(BlindXiangqiEngine.canMove(general, BlindColor.RED, 4, 7, 3, 6)).isFalse();
        assertThat(BlindXiangqiEngine.canMove(general, BlindColor.RED, 4, 7, 4, 5)).isFalse();

        List<Piece> advisor = List.of(
                revealed("a1", BlindColor.RED, BlindPieceType.ADVISOR, 4, 7));
        assertThat(BlindXiangqiEngine.canMove(advisor, BlindColor.RED, 4, 7, 3, 6)).isTrue();
        assertThat(BlindXiangqiEngine.canMove(advisor, BlindColor.RED, 4, 7, 4, 6)).isFalse();

        List<Piece> blackGeneral = List.of(
                revealed("g2", BlindColor.BLACK, BlindPieceType.GENERAL, 4, 2));
        assertThat(BlindXiangqiEngine.canMove(blackGeneral, BlindColor.BLACK, 4, 2, 4, 3)).isTrue();
        assertThat(BlindXiangqiEngine.canMove(blackGeneral, BlindColor.BLACK, 4, 2, 4, 4)).isFalse();
    }

    @Test
    void soldierMovesForwardAndSidewaysOnlyAfterCrossingRiver() {
        List<Piece> beforeRiver = List.of(
                revealed("s1", BlindColor.RED, BlindPieceType.SOLDIER, 4, 6));
        assertThat(BlindXiangqiEngine.canMove(beforeRiver, BlindColor.RED, 4, 6, 4, 5)).isTrue();
        assertThat(BlindXiangqiEngine.canMove(beforeRiver, BlindColor.RED, 4, 6, 5, 6)).isFalse();

        List<Piece> afterRiver = List.of(
                revealed("s1", BlindColor.RED, BlindPieceType.SOLDIER, 4, 4));
        assertThat(BlindXiangqiEngine.canMove(afterRiver, BlindColor.RED, 4, 4, 5, 4)).isTrue();
        assertThat(BlindXiangqiEngine.canMove(afterRiver, BlindColor.RED, 4, 4, 4, 5)).isFalse();

        List<Piece> blackBeforeRiver = List.of(
                revealed("s2", BlindColor.BLACK, BlindPieceType.SOLDIER, 4, 3));
        assertThat(BlindXiangqiEngine.canMove(
                blackBeforeRiver, BlindColor.BLACK, 4, 3, 4, 4)).isTrue();
        assertThat(BlindXiangqiEngine.canMove(
                blackBeforeRiver, BlindColor.BLACK, 4, 3, 5, 3)).isFalse();

        List<Piece> blackAfterRiver = List.of(
                revealed("s2", BlindColor.BLACK, BlindPieceType.SOLDIER, 4, 5));
        assertThat(BlindXiangqiEngine.canMove(
                blackAfterRiver, BlindColor.BLACK, 4, 5, 5, 5)).isTrue();
        assertThat(BlindXiangqiEngine.canMove(
                blackAfterRiver, BlindColor.BLACK, 4, 5, 4, 4)).isFalse();
    }

    @Test
    void unrevealedOrOpponentPiecesCannotBeMoved() {
        List<Piece> hidden = List.of(
                hidden("r1", BlindColor.RED, BlindPieceType.CHARIOT, 0, 9));
        assertThat(BlindXiangqiEngine.canMove(hidden, BlindColor.RED, 0, 9, 0, 8)).isFalse();

        List<Piece> opponent = List.of(
                revealed("b1", BlindColor.BLACK, BlindPieceType.CHARIOT, 0, 9));
        assertThat(BlindXiangqiEngine.canMove(opponent, BlindColor.RED, 0, 9, 0, 8)).isFalse();
    }

    @Test
    void ownRevealedPieceCannotBeCapturedButOwnHiddenPieceCan() {
        List<Piece> ownRevealed = List.of(
                revealed("r1", BlindColor.RED, BlindPieceType.CHARIOT, 0, 9),
                revealed("r2", BlindColor.RED, BlindPieceType.SOLDIER, 0, 7));
        assertThat(BlindXiangqiEngine.canMove(ownRevealed, BlindColor.RED, 0, 9, 0, 7)).isFalse();

        List<Piece> ownHidden = List.of(
                revealed("r1", BlindColor.RED, BlindPieceType.CHARIOT, 0, 9),
                hidden("r2", BlindColor.RED, BlindPieceType.SOLDIER, 0, 7));
        assertThat(BlindXiangqiEngine.canMove(ownHidden, BlindColor.RED, 0, 9, 0, 7)).isTrue();
    }

    @Test
    void generalsFacingNeedsBothRevealedAndClearFile() {
        List<Piece> facing = List.of(
                revealed("rg", BlindColor.RED, BlindPieceType.GENERAL, 4, 8),
                revealed("bg", BlindColor.BLACK, BlindPieceType.GENERAL, 4, 1));
        assertThat(BlindXiangqiEngine.generalsFacing(facing)).isTrue();

        List<Piece> oneHidden = List.of(
                revealed("rg", BlindColor.RED, BlindPieceType.GENERAL, 4, 8),
                hidden("bg", BlindColor.BLACK, BlindPieceType.GENERAL, 4, 1));
        assertThat(BlindXiangqiEngine.generalsFacing(oneHidden)).isFalse();

        List<Piece> blocked = List.of(
                revealed("rg", BlindColor.RED, BlindPieceType.GENERAL, 4, 8),
                revealed("bg", BlindColor.BLACK, BlindPieceType.GENERAL, 4, 1),
                hidden("s", BlindColor.RED, BlindPieceType.SOLDIER, 4, 5));
        assertThat(BlindXiangqiEngine.generalsFacing(blocked)).isFalse();

        List<Piece> differentFile = List.of(
                revealed("rg", BlindColor.RED, BlindPieceType.GENERAL, 3, 8),
                revealed("bg", BlindColor.BLACK, BlindPieceType.GENERAL, 4, 1));
        assertThat(BlindXiangqiEngine.generalsFacing(differentFile)).isFalse();
    }

    private static void assertArmyComposition(List<Placement> placements) {
        for (BlindColor color : BlindColor.values()) {
            Map<BlindPieceType, Long> counts = placements.stream()
                    .filter(piece -> piece.color() == color)
                    .map(Placement::type)
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            assertThat(counts)
                    .containsEntry(BlindPieceType.GENERAL, 1L)
                    .containsEntry(BlindPieceType.ADVISOR, 2L)
                    .containsEntry(BlindPieceType.ELEPHANT, 2L)
                    .containsEntry(BlindPieceType.HORSE, 2L)
                    .containsEntry(BlindPieceType.CHARIOT, 2L)
                    .containsEntry(BlindPieceType.CANNON, 2L)
                    .containsEntry(BlindPieceType.SOLDIER, 5L);
        }
    }

    private static Set<String> standardCells() {
        Set<String> cells = new HashSet<>();
        List<int[]> coordinates = new ArrayList<>();
        for (int x = 0; x < 9; x += 1) {
            coordinates.add(new int[]{x, 0});
        }
        coordinates.add(new int[]{1, 2});
        coordinates.add(new int[]{7, 2});
        for (int x = 0; x < 9; x += 2) {
            coordinates.add(new int[]{x, 3});
        }
        for (int x = 0; x < 9; x += 1) {
            coordinates.add(new int[]{x, 9});
        }
        coordinates.add(new int[]{1, 7});
        coordinates.add(new int[]{7, 7});
        for (int x = 0; x < 9; x += 2) {
            coordinates.add(new int[]{x, 6});
        }
        for (int[] coordinate : coordinates) {
            cells.add(coordinate[0] + "," + coordinate[1]);
        }
        return cells;
    }

    private static Piece hidden(String id, BlindColor color, BlindPieceType type, int x, int y) {
        return new Piece(id, color, type, x, y, false);
    }

    private static Piece revealed(String id, BlindColor color, BlindPieceType type, int x, int y) {
        return new Piece(id, color, type, x, y, true);
    }
}
