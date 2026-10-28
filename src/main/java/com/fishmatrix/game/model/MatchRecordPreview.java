package com.fishmatrix.game.model;

/**
 * 比赛记录列表的预览行：只带对阵与结果摘要，不含小局、落子与聊天，
 * 详情仍然走 {@code /api/v1/game/matches/{id}}。
 */
public record MatchRecordPreview(
        String matchId,
        String tournamentId,
        String tournamentName,
        String gameId,
        String kind,
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
        long createdAt,
        Long finishedAt,
        boolean visible,
        int displayOrder) {
}
