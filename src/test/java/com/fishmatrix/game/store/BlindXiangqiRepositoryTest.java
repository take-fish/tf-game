package com.fishmatrix.game.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.fishmatrix.game.model.BlindLayout;
import com.fishmatrix.game.service.BlindXiangqiEngine;
import com.fishmatrix.game.service.BlindXiangqiEngine.Piece;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.cloud.nacos.discovery.enabled=false",
            "spring.cloud.nacos.config.enabled=false",
            "app.game.scheduling-enabled=false"
        })
class BlindXiangqiRepositoryTest {

    @Autowired
    private BlindXiangqiRepository pieces;

    @Test
    void persistsAndMutatesServerSidePieces() {
        String roundId = UUID.randomUUID().toString();
        List<Piece> initial = BlindXiangqiEngine.generateLayout(BlindLayout.STANDARD_SHUFFLE).stream()
                .map(placement -> new Piece(
                        UUID.randomUUID().toString(), placement.color(), placement.type(),
                        placement.x(), placement.y(), false))
                .toList();

        pieces.insertPieces(roundId, initial);

        List<Piece> loaded = pieces.listAlivePieces(roundId);
        assertThat(loaded).hasSize(32);
        assertThat(loaded).allSatisfy(piece -> assertThat(piece.revealed()).isFalse());

        Piece first = loaded.get(0);
        assertThat(pieces.reveal(roundId, first.id())).isEqualTo(1);
        Piece revealed = find(roundId, first.id());
        assertThat(revealed.revealed()).isTrue();
        assertThat(pieces.reveal(roundId, first.id())).isZero();

        int[] free = freeCell(loaded);
        assertThat(pieces.move(roundId, first.id(), free[0], free[1])).isEqualTo(1);
        Piece moved = find(roundId, first.id());
        assertThat(moved.x()).isEqualTo(free[0]);
        assertThat(moved.y()).isEqualTo(free[1]);

        assertThat(pieces.capture(roundId, first.id())).isEqualTo(1);
        assertThat(pieces.listAlivePieces(roundId)).hasSize(31);
        assertThat(pieces.capture(roundId, first.id())).isZero();
    }

    private Piece find(String roundId, String pieceId) {
        return pieces.listAlivePieces(roundId).stream()
                .filter(piece -> piece.id().equals(pieceId))
                .findFirst()
                .orElseThrow();
    }

    private static int[] freeCell(List<Piece> pieces) {
        Set<String> occupied = pieces.stream()
                .map(piece -> piece.x() + "," + piece.y())
                .collect(Collectors.toSet());
        for (int y = 0; y < BlindXiangqiEngine.HEIGHT; y += 1) {
            for (int x = 0; x < BlindXiangqiEngine.WIDTH; x += 1) {
                if (!occupied.contains(x + "," + y)) {
                    return new int[]{x, y};
                }
            }
        }
        throw new AssertionError("没有空位");
    }
}
