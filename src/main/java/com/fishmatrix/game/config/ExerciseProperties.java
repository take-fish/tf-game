package com.fishmatrix.game.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 集群巡检对抗（2D 轻 FPS）参数。
 * 服务端固定 25Hz 积分步长、12.5Hz 状态广播，单实例内存房间。
 */
@ConfigurationProperties(prefix = "app.game.exercise")
public record ExerciseProperties(
        boolean enabled,
        int tickMillis,
        int broadcastMillis,
        int snapshotSeconds,
        int roundSeconds,
        int maxRooms,
        int maxPlayers,
        int inputsPerSecond,
        double perceptionRange) {

    public ExerciseProperties {
        if (tickMillis < 25) {
            tickMillis = 40;
        }
        if (broadcastMillis < 40) {
            broadcastMillis = 80;
        }
        if (snapshotSeconds <= 0) {
            snapshotSeconds = 1;
        }
        if (roundSeconds <= 0) {
            roundSeconds = 180;
        }
        if (maxRooms <= 0) {
            maxRooms = 50;
        }
        if (maxPlayers < 2 || maxPlayers > 8) {
            maxPlayers = 4;
        }
        if (inputsPerSecond <= 0) {
            inputsPerSecond = 30;
        }
        if (perceptionRange <= 0) {
            perceptionRange = 380;
        }
    }
}
