package com.fishmatrix.game.model;

/**
 * 创建巡检对抗房间。mode 取值 FFA / TEAM / TRAINING（单人训练）。
 */
public record CreateExerciseRoomRequest(String mode, Integer maxPlayers) {
}
