package com.fishmatrix.game.model;

/**
 * 一场比赛（BO1/BO3/BO5）。currentRound 只在详情接口中填充，
 * 列表接口为 null，避免对阵图查询时产生 N+1。
 * firstGameId/gameChooserUserId 用于页面淘汰赛的首局剧本选择；blindLayout 仅盲棋使用。
 */
public record MatchView(
        String id,
        String tournamentId,
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
        String nextMatchId,
        String firstGameId,
        String gameChooserUserId,
        long createdAt,
        Long startedAt,
        Long finishedAt,
        RoundView currentRound,
        String blindLayout) {

    /** 兼容旧调用：未显式指定游戏时按五子棋处理。 */
    public MatchView(
            String id,
            String tournamentId,
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
            String nextMatchId,
            long createdAt,
            Long startedAt,
            Long finishedAt,
            RoundView currentRound) {
        this(id, tournamentId, GameIds.HEATMAP_GOMOKU, kind, roundNo, slotNo, stage, status,
                player1UserId, player1Nickname, player2UserId, player2Nickname,
                player1Score, player2Score, winnerUserId, nextMatchId, null, null,
                createdAt, startedAt, finishedAt, currentRound, null);
    }

    /** 兼容尚无首局选图字段与盲棋摆法的调用。 */
    public MatchView(
            String id, String tournamentId, String gameId, String kind, int roundNo,
            int slotNo, String stage, String status, String player1UserId,
            String player1Nickname, String player2UserId, String player2Nickname,
            int player1Score, int player2Score, String winnerUserId,
            String nextMatchId, long createdAt, Long startedAt, Long finishedAt,
            RoundView currentRound) {
        this(id, tournamentId, gameId, kind, roundNo, slotNo, stage, status,
                player1UserId, player1Nickname, player2UserId, player2Nickname,
                player1Score, player2Score, winnerUserId, nextMatchId, null, null,
                createdAt, startedAt, finishedAt, currentRound, null);
    }

    /** 兼容盲棋旧调用：gameId 与 blindLayout 位于视图末尾。 */
    public MatchView(
            String id,
            String tournamentId,
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
            String nextMatchId,
            long createdAt,
            Long startedAt,
            Long finishedAt,
            RoundView currentRound,
            String gameId,
            String blindLayout) {
        this(id, tournamentId, gameId, kind, roundNo, slotNo, stage, status,
                player1UserId, player1Nickname, player2UserId, player2Nickname,
                player1Score, player2Score, winnerUserId, nextMatchId, null, null,
                createdAt, startedAt, finishedAt, currentRound, blindLayout);
    }
}
