package com.fishmatrix.game.model;

import java.util.List;

/**
 * 小局公开视图。盲棋额外下发公开棋盘；firstUserId 为先手（盲棋中即红方）。
 */
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
        List<MoveView> moves,
        List<BlindPieceView> blindBoard,
        String firstUserId,
        String secondUserId) {
}
