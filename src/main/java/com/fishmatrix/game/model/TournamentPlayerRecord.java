package com.fishmatrix.game.model;

public record TournamentPlayerRecord(
        String tournamentId,
        String userId,
        String nickname,
        int seed,
        String status,
        long invitedAt,
        Long respondedAt) {
}
