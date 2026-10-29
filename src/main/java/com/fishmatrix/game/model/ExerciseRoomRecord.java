package com.fishmatrix.game.model;

/**
 * 巡检房间持久化行：服务重启后按此恢复仍在进行的房间。
 * 字段顺序与旧仓储的内部行保持一致，便于逐字段对照。
 */
public record ExerciseRoomRecord(
        String id,
        String roomCode,
        String mode,
        String source,
        String status,
        String matchId,
        String ownerUserId,
        int maxPlayers,
        int durationSeconds,
        String stateJson,
        int stateVersion,
        String winnerUserId,
        String winnerTeam,
        String finishReason,
        long createdAt,
        Long startedAt,
        Long finishedAt) {
}
