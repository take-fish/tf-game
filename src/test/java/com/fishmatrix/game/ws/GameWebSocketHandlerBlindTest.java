package com.fishmatrix.game.ws;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.service.GameChatService;
import com.fishmatrix.game.service.MatchService;
import com.fishmatrix.game.service.TournamentService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class GameWebSocketHandlerBlindTest {

    private MatchService matches;
    private GameSessionRegistry registry;
    private GameSession session;
    private WebSocketSession socket;
    private GameWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        matches = mock(MatchService.class);
        registry = mock(GameSessionRegistry.class);
        socket = mock(WebSocketSession.class);
        session = new GameSession(socket, new GameIdentity(1, "u1", "甲", "USER"));
        when(registry.get(any())).thenReturn(session);
        handler = new GameWebSocketHandler(
                mock(TournamentService.class),
                matches,
                mock(GameChatService.class),
                registry,
                new ObjectMapper());
    }

    @Test
    void revealFrameRoutesToMatchService() {
        MatchView view = view();
        when(matches.revealPiece(session.getIdentity(), "m1", 1, 2)).thenReturn(view);

        handler.handleTextMessage(socket, new TextMessage(
                "{\"type\":\"reveal_piece\",\"matchId\":\"m1\",\"x\":1,\"y\":2}"));

        verify(matches).revealPiece(session.getIdentity(), "m1", 1, 2);
        verify(registry).send(session, Map.of("type", "match_update", "match", view));
    }

    @Test
    void moveFrameRoutesToMatchService() {
        MatchView view = view();
        when(matches.movePiece(session.getIdentity(), "m1", 0, 9, 0, 8)).thenReturn(view);

        handler.handleTextMessage(socket, new TextMessage(
                "{\"type\":\"move_piece\",\"matchId\":\"m1\","
                        + "\"fromX\":0,\"fromY\":9,\"toX\":0,\"toY\":8}"));

        verify(matches).movePiece(session.getIdentity(), "m1", 0, 9, 0, 8);
        verify(registry).send(session, Map.of("type", "match_update", "match", view));
    }

    private static MatchView view() {
        return new MatchView(
                "m1", "t1", "TOURNAMENT", 1, 1, "KNOCKOUT", "RUNNING",
                "u1", "甲", "u2", "乙", 0, 0, null, null,
                1_000L, null, null, null, "blind-xiangqi", "STANDARD_SHUFFLE");
    }
}
