package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.converter.TournamentConverters;
import com.fishmatrix.game.mapper.GameChatMessageMapper;
import com.fishmatrix.game.mapper.MatchMapper;
import com.fishmatrix.game.mapper.TournamentMapper;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.SendChatRequest;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.ws.GameSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameChatServiceDuelTest {

    private GameChatService chats;

    @BeforeEach
    void setUp() {
        TournamentMapper tournaments = mock(TournamentMapper.class);
        when(tournaments.selectActiveById("d1"))
                .thenReturn(TournamentConverters.toEntity(duel()));
        chats = new GameChatService(
                mock(GameChatMessageMapper.class),
                tournaments,
                mock(MatchMapper.class),
                mock(GameSessionRegistry.class),
                new GameProperties(2, 16, 300, 60, 30, 60, 60, 600, 1000, "*"));
    }

    @Test
    void duelHasNoPublicChat() {
        assertThatThrownBy(() -> chats.send(identity(), new SendChatRequest(
                "d1", null, "PUBLIC", null, "hello")))
                .isInstanceOf(GameException.class)
                .hasMessage("约战不提供聊天");
    }

    @Test
    void duelHasNoPlayerDm() {
        assertThatThrownBy(() -> chats.send(identity(), new SendChatRequest(
                "d1", "m1", "PLAYER_DM", null, "hello")))
                .isInstanceOf(GameException.class)
                .hasMessage("约战不提供聊天");
    }

    @Test
    void duelHasNoChatHistory() {
        assertThatThrownBy(() -> chats.history(identity(), "d1", null, "PUBLIC", 50))
                .isInstanceOf(GameException.class)
                .hasMessage("约战不提供聊天");
    }

    private GameIdentity identity() {
        return new GameIdentity(1L, "u1", "甲", "USER");
    }

    private TournamentRecord duel() {
        return new TournamentRecord(
                "d1", "一对一约战", "heatmap-gomoku", TournamentService.KIND_DUEL,
                "BO1", "KNOCKOUT", "RUNNING", "u1", "甲", 2, 10_000L,
                1, 1, null, null, 1_000L, 2_000L, null);
    }
}
