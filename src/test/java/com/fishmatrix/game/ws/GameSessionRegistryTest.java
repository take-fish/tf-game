package com.fishmatrix.game.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.GameOnlineMember;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class GameSessionRegistryTest {

    private final GameSessionRegistry registry = new GameSessionRegistry(new ObjectMapper());

    @Test
    void onlineMembersDedupeConnectionsAndKeepUserCenterNickname() {
        register("s1", 12, "u12", "良一情");
        register("s2", 12, "u12", "良一情");
        register("s3", 5, "u5", "早九晚八240");

        assertThat(registry.onlineMembers()).containsExactlyInAnyOrder(
                new GameOnlineMember("u12", "良一情"),
                new GameOnlineMember("u5", "早九晚八240"));
        assertThat(registry.onlineUserIds()).containsExactlyInAnyOrder("u12", "u5");
    }

    private void register(String sessionId, long accountId, String userId, String nickname) {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(sessionId);
        registry.register(socket, new GameIdentity(accountId, userId, nickname, "USER"));
    }
}
