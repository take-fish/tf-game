package com.fishmatrix.game.model;

public record LeaderboardEntry(long rank, String userId, String nickname, long score) {
}
