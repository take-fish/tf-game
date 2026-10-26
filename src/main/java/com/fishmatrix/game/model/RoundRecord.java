package com.fishmatrix.game.model;

public record RoundRecord(
        String id,
        String matchId,
        int gameNo,
        String status,
        String blackUserId,
        String whiteUserId,
        String turnUserId,
        String winnerUserId,
        int moveCount,
        long lastMoveAt,
        Long restUntil,
        String readyUserIds,
        long startedAt,
        Long finishedAt) {
}
