package com.fishmatrix.game.model;

public record ChatMessageView(
        String id,
        String tournamentId,
        String matchId,
        String channel,
        String fromUserId,
        String fromNickname,
        String toUserId,
        String content,
        long createdAt) {
}
