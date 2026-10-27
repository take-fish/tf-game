package com.fishmatrix.game.arena;

import java.util.List;

public record ArenaContext(
        String gameId,
        List<String> players,
        String attackerUserId,
        long startedAt,
        long endsAt,
        long seed) {
}
