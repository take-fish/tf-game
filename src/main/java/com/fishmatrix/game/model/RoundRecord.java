package com.fishmatrix.game.model;

/**
 * 一场比赛中的一个小局。五子棋使用落子表，实时竞技场使用 state_json 保存权威快照。
 */
public record RoundRecord(
        String id,
        String matchId,
        String gameId,
        int gameNo,
        String status,
        String blackUserId,
        String whiteUserId,
        String attackerUserId,
        String turnUserId,
        String winnerUserId,
        int moveCount,
        long lastMoveAt,
        Long restUntil,
        String readyUserIds,
        String stateJson,
        int stateVersion,
        long startedAt,
        Long endsAt,
        Long finishedAt) {
}
