package com.fishmatrix.game.model;

/**
 * 比赛 WebSocket 入站帧。type 取值：
 * ping / watch_match / leave_match / match_ready / place_stone /
 * reveal_piece / move_piece / resign / chat / refresh_lobby。
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
        Integer toY) {
}
