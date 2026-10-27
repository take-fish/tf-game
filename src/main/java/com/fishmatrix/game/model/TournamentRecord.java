package com.fishmatrix.game.model;

public record TournamentRecord(
        String id,
        String name,
        String gameId,
        String kind,
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
        String runnerUpUserId,
        String runnerUpNickname,
        long createdAt,
        Long startedAt,
        Long finishedAt) {

    /** 兼容旧调用。 */
    public TournamentRecord(
            String id, String name, String gameId, String kind, String format,
            String bracketType, String status, String creatorUserId,
            String creatorNickname, int targetPlayers, long inviteDeadline,
            int currentRound, int totalRounds, String championUserId,
            String championNickname, long createdAt, Long startedAt, Long finishedAt) {
        this(id, name, gameId, kind, format, bracketType, status, creatorUserId,
                creatorNickname, targetPlayers, inviteDeadline, currentRound,
                totalRounds, championUserId, championNickname, null, null,
                createdAt, startedAt, finishedAt);
    }
}
