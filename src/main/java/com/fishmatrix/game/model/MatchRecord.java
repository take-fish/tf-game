package com.fishmatrix.game.model;

/**
 * 一场 BO 对阵。firstGameId 是首局剧本：页面淘汰赛中由高顺位选手选择，
 * 其余赛事由系统随机抽取；gameChooserUserId 标识唯一有权选择首局剧本的选手。
 */
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
        Long finishedAt,
        String firstGameId,
        String gameChooserUserId) {

    /** 兼容旧调用：历史数据没有首局剧本与选图人。 */
    public MatchRecord(
            String id, String tournamentId, int roundNo, int slotNo, String stage,
            String status, String player1UserId, String player1Nickname,
            String player2UserId, String player2Nickname, int player1Score,
            int player2Score, String winnerUserId, String nextMatchId,
            long createdAt, Long startedAt, Long finishedAt) {
        this(id, tournamentId, roundNo, slotNo, stage, status, player1UserId,
                player1Nickname, player2UserId, player2Nickname, player1Score,
                player2Score, winnerUserId, nextMatchId, createdAt, startedAt,
                finishedAt, null, null);
    }
}
