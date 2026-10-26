package com.fishmatrix.game.model;

/**
 * 一场比赛（BO1/BO3/BO5）。currentRound 只在详情接口中填充，
 * 列表接口为 null，避免对阵图查询时产生 N+1。
 */
public record MatchView(
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
        Long finishedAt,
        RoundView currentRound) {
}
