package com.fishmatrix.game.model;

import java.util.List;

public record RoundView(
        String id,
        int gameNo,
        String status,
        String blackUserId,
        String whiteUserId,
        String turnUserId,
        String winnerUserId,
        int moveCount,
        long lastMoveAt,
        Long restUntil,
        List<String> readyUserIds,
        long startedAt,
        Long finishedAt,
        List<MoveView> moves) {
}
