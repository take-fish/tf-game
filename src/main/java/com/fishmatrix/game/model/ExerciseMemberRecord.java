package com.fishmatrix.game.model;

/** 巡检房间成员的持久化行。 */
public record ExerciseMemberRecord(
        String roomId,
        String userId,
        String nickname,
        String team,
        boolean ready,
        boolean bot,
        int score,
        int kills,
        int deaths,
        int assists,
        long joinedAt,
        Long leftAt) {
}
