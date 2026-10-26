package com.fishmatrix.game.model;

import java.util.List;

public record TournamentView(
        String id,
        String name,
        String gameId,
        String format,
        String bracketType,
        String status,
        String creatorUserId,
        String creatorNickname,
        int targetPlayers,
        long inviteDeadline,
        int currentRound,
        int totalRounds,
        String championUserId,
        String championNickname,
        long createdAt,
        Long startedAt,
        Long finishedAt,
        List<PlayerView> players,
        List<MatchView> matches) {
}
