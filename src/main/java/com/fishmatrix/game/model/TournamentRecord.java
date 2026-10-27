package com.fishmatrix.game.model;

import java.util.List;

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
        Long finishedAt,
        String blindLayout,
        List<String> gamePool) {

    /** 兼容旧调用：没有冠亚军扩展字段与盲棋摆法。 */
    public TournamentRecord(
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
            long createdAt,
            Long startedAt,
            Long finishedAt) {
        this(id, name, gameId, kind, format, bracketType, status,
                creatorUserId, creatorNickname, targetPlayers, inviteDeadline,
                currentRound, totalRounds, championUserId, championNickname,
                null, null, createdAt, startedAt, finishedAt, null, null);
    }
}
