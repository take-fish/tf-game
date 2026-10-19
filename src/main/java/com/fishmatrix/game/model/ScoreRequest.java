package com.fishmatrix.game.model;

public record ScoreRequest(String gameId, String userId, String nickname, long score) {
}
