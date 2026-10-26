package com.fishmatrix.game.model;

import java.util.List;

public record CreateTournamentRequest(
        String name,
        String format,
        List<PlayerRef> players) {
}
