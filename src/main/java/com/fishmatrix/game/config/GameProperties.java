package com.fishmatrix.game.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 比赛域配置。默认值面向本地开发，生产/测试可在 Nacos 中覆盖。
 */
@ConfigurationProperties(prefix = "app.game")
public record GameProperties(
        int minPlayers,
        int maxPlayers,
        int inviteTimeoutSeconds,
        int restSeconds,
        int moveTimeoutSeconds,
        int disconnectTimeoutSeconds,
        int wsTicketTtlSeconds,
        int notificationTtlSeconds,
        int maxChatLength,
        String allowedOrigins) {

    public GameProperties {
        if (minPlayers < 2) {
            minPlayers = 2;
        }
        if (maxPlayers < minPlayers) {
            maxPlayers = 16;
        }
        if (maxPlayers > 32) {
            maxPlayers = 32;
        }
        if (inviteTimeoutSeconds <= 0) {
            inviteTimeoutSeconds = 300;
        }
        if (restSeconds <= 0) {
            restSeconds = 60;
        }
        if (moveTimeoutSeconds <= 0) {
            moveTimeoutSeconds = 30;
        }
        if (disconnectTimeoutSeconds <= 0) {
            disconnectTimeoutSeconds = 60;
        }
        if (wsTicketTtlSeconds <= 0) {
            wsTicketTtlSeconds = 60;
        }
        if (notificationTtlSeconds <= 0) {
            notificationTtlSeconds = 600;
        }
        if (maxChatLength <= 0) {
            maxChatLength = 1000;
        }
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            allowedOrigins = "*";
        }
    }

    public List<String> originPatterns() {
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
