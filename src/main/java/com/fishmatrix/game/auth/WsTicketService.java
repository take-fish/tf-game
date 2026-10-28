package com.fishmatrix.game.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.model.GameIdentity;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 一次性 WebSocket 握手票据：避免把长期 Bearer token 放进 URL / 访问日志。
 */
@RequiredArgsConstructor
@Service
public class WsTicketService {

    private static final String PREFIX = "tf:game:ws-ticket:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final GameProperties properties;

    public Ticket issue(GameIdentity identity) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            redis.opsForValue().set(
                    PREFIX + ticket,
                    objectMapper.writeValueAsString(identity),
                    Duration.ofSeconds(properties.wsTicketTtlSeconds()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("WebSocket ticket 序列化失败", exception);
        }
        return new Ticket(ticket, properties.wsTicketTtlSeconds());
    }

    public Optional<GameIdentity> consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        String payload = redis.opsForValue().getAndDelete(PREFIX + ticket);
        if (payload == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, GameIdentity.class));
        } catch (JsonProcessingException exception) {
            return Optional.empty();
        }
    }

    public record Ticket(String ticket, int expiresIn) {
    }
}
