package com.fishmatrix.game.model;

/**
 * 比赛 WebSocket 入站帧。type 取值：
 * ping / watch_match / leave_match / match_ready / place_stone /
 * reveal_piece / move_piece / resign / chat / refresh_lobby /
 * arena_action / choose_game /
 * exercise_join / exercise_leave / exercise_ready / exercise_input / exercise_rooms。
 * 盲棋使用 fromX/fromY/toX/toY 表示翻棋与走子坐标。
 * 竞技场动作使用 action(动作类型) / target(节点或连线) / skill(技能) / cell(格子序号)
 * / actionId(客户端幂等 ID)。
 * choose_game 使用 gameId 指定页面淘汰赛首局剧本。
 * 巡检输入使用 roomId/roomCode 定位房间，moveX/moveY/aim/fire/skill 为意图。
 */
public record WsInbound(
        String type,
        String tournamentId,
        String matchId,
        String roundId,
        String channel,
        String toUserId,
        String content,
        Integer x,
        Integer y,
        Integer fromX,
        Integer fromY,
        Integer toX,
        Integer toY,
        String action,
        String target,
        String skill,
        Integer cell,
        String actionId,
        String gameId,
        String roomId,
        String roomCode,
        Integer inputSeq,
        Double moveX,
        Double moveY,
        Double aim,
        Boolean fire,
        Double skillX,
        Double skillY) {

    public WsInbound(
            String type, String tournamentId, String matchId, String roundId,
            String channel, String toUserId, String content, Integer x, Integer y,
            String action, String target, String skill, Integer cell, String actionId) {
        this(type, tournamentId, matchId, roundId, channel, toUserId, content,
                x, y, null, null, null, null, action, target, skill, cell, actionId, null,
                null, null, null, null, null, null, null, null, null);
    }

    public WsInbound(
            String type, String tournamentId, String matchId, String roundId,
            String channel, String toUserId, String content, Integer x, Integer y,
            String action, String target, String skill, Integer cell, String actionId,
            String gameId) {
        this(type, tournamentId, matchId, roundId, channel, toUserId, content,
                x, y, null, null, null, null, action, target, skill, cell, actionId, gameId,
                null, null, null, null, null, null, null, null, null);
    }
}
