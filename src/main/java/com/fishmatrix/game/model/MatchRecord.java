package com.fishmatrix.game.model;

public record MatchRecord(
        String id,
        String tournamentId,
        int roundNo,
        int slotNo,
        String stage,
        String status,
        String player1UserId,
        String player1Nickname,
        String player2UserId,
        String player2Nickname,
        int player1Score,
        int player2Score,
        String winnerUserId,
        String nextMatchId,
        long createdAt,
        Long startedAt,
        Long finishedAt) {
}
