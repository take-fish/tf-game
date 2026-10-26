package com.fishmatrix.game.model;

public record TournamentRecord(
        String id,
        String name,
        String gameId,
        String format,
        String bracketType,
        String status,
        String creatorUserId,
        String creatorNickname,
        int targetPlayers,
        long inviteDeadline,
        int currentRound,
        int totalRounds,
        String championUserId,
        String championNickname,
        long createdAt,
        Long startedAt,
        Long finishedAt) {
}
