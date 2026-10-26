package com.fishmatrix.game.model;

public record PlayerView(
        String userId,
        String nickname,
        int seed,
        String status) {
}
