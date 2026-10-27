package com.fishmatrix.game.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 实时竞技场参数。生产/测试可通过 Nacos 调整节奏与数值，不改代码。
 */
@ConfigurationProperties(prefix = "app.game.arena")
public record ArenaProperties(
        boolean enabled,
        int tickMillis,
        int snapshotSeconds,
        int actionsPerSecond,
        int restSeconds,
        int heatmapRoundSeconds,
        int topologyRoundSeconds) {

    public ArenaProperties {
        if (tickMillis < 100) {
            tickMillis = 200;
        }
        if (snapshotSeconds <= 0) {
            snapshotSeconds = 1;
        }
        if (actionsPerSecond <= 0) {
            actionsPerSecond = 10;
        }
        if (restSeconds <= 0) {
            restSeconds = 20;
        }
        if (heatmapRoundSeconds <= 0) {
            heatmapRoundSeconds = 120;
        }
        if (topologyRoundSeconds <= 0) {
            topologyRoundSeconds = 90;
        }
    }
}
