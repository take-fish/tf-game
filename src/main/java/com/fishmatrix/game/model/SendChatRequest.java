package com.fishmatrix.game.model;

/**
 * 比赛聊天发送请求。channel 取值 PUBLIC / PLAYER_DM。
 * PLAYER_DM 的收件人由服务端根据比赛双方推导，toUserId 仅用于前端回显校验。
 */
public record SendChatRequest(
        String tournamentId,
        String matchId,
        String channel,
        String toUserId,
        String content) {
}
