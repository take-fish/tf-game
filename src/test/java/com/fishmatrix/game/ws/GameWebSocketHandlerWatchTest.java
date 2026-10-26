package com.fishmatrix.game.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.service.GameChatService;
import com.fishmatrix.game.service.MatchService;
import com.fishmatrix.game.service.TournamentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class GameWebSocketHandlerWatchTest {

    private MatchService matches;
    private GameSessionRegistry registry;
    private GameSession session;
    private GameWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        matches = mock(MatchService.class);
        registry = mock(GameSessionRegistry.class);
        handler = new GameWebSocketHandler(
                mock(TournamentService.class),
                matches,
                mock(GameChatService.class),
                registry,
                new ObjectMapper());
        session = new GameSession(mock(WebSocketSession.class),
                new GameIdentity(2, "u2", "Bob", "USER"));
    }

    @Test
    void forbiddenWatchDoesNotSubscribe() {
        GameIdentity identity = session.getIdentity();
        when(matches.detailForUser(identity, "d1"))
                .thenThrow(GameException.forbidden("约战仅双方可见"));

        assertThatThrownBy(() -> handler.watchMatch(session, "d1"))
                .isInstanceOf(GameException.class)
                .hasMessage("约战仅双方可见");

        assertThat(session.getWatchingMatchId()).isNull();
        verify(registry, never()).send(any(), any());
    }

    @Test
    void allowedWatchSubscribesAndSendsUpdate() {
        MatchView view = new MatchView(
                "d1", "duel", "DUEL", 1, 1, "KNOCKOUT", "READY",
                "u1", "Alice", "u2", "Bob", 0, 0, null, null,
                1_000L, null, null, null);
        GameIdentity identity = session.getIdentity();
        when(matches.detailForUser(identity, "d1")).thenReturn(view);

        handler.watchMatch(session, "d1");

        assertThat(session.getWatchingMatchId()).isEqualTo("d1");
        verify(registry).send(session, java.util.Map.of(
                "type", "match_update",
                "match", view));
    }
}
