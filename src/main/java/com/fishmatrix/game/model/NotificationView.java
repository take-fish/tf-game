package com.fishmatrix.game.model;

public record NotificationView(
        String id,
        String type,
        String tournamentId,
        String matchId,
        String title,
        String message,
        long createdAt,
        long expiresAt) {
}
