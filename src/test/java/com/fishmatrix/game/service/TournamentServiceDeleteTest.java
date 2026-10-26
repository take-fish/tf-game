package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.store.MatchRepository;
import com.fishmatrix.game.store.PlayerStatRepository;
import com.fishmatrix.game.store.TournamentRepository;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TournamentServiceDeleteTest {

    private TournamentRepository tournaments;
    private GameSessionRegistry registry;
    private TournamentService service;

    @BeforeEach
    void setUp() {
        tournaments = mock(TournamentRepository.class);
        registry = mock(GameSessionRegistry.class);
        service = new TournamentService(
                tournaments,
                mock(MatchRepository.class),
                mock(PlayerStatRepository.class),
                registry,
                mock(GameNotificationService.class),
                new GameProperties(2, 16, 300, 60, 30, 60, 60, 600, 1000, "*"));
    }

    @Test
    void creatorDeletesFinishedTournament() {
        when(tournaments.findById("t1")).thenReturn(Optional.of(tournament("FINISHED", "u1")));
        when(tournaments.softDelete(eq("t1"), anyLong())).thenReturn(1);

        service.delete(identity("u1", "USER"), "t1");

        verify(tournaments).softDelete(eq("t1"), anyLong());
        verify(registry).broadcastAll(Map.of("type", "tournament_deleted", "tournamentId", "t1"));
    }

    @Test
    void runningTournamentCannotBeDeleted() {
        when(tournaments.findById("t1")).thenReturn(Optional.of(tournament("RUNNING", "u1")));

        assertThatThrownBy(() -> service.delete(identity("u1", "USER"), "t1"))
                .isInstanceOf(GameException.class)
                .hasMessage("赛事进行中，不能删除");

        verify(tournaments, never()).softDelete(eq("t1"), anyLong());
        verifyNoInteractions(registry);
    }

    @Test
    void nonCreatorCannotDelete() {
        when(tournaments.findById("t1")).thenReturn(Optional.of(tournament("FINISHED", "u1")));

        assertThatThrownBy(() -> service.delete(identity("u2", "USER"), "t1"))
                .isInstanceOf(GameException.class)
                .hasMessage("只有创建者可以删除赛事");

        verify(tournaments, never()).softDelete(eq("t1"), anyLong());
    }

    @Test
    void adminCanDeleteAnyTournament() {
        when(tournaments.findById("t1")).thenReturn(Optional.of(tournament("CANCELLED", "u1")));
        when(tournaments.softDelete(eq("t1"), anyLong())).thenReturn(1);

        service.delete(identity("u9", "ADMIN"), "t1");

        verify(tournaments).softDelete(eq("t1"), anyLong());
    }

    @Test
    void alreadyDeletedTournamentIsNotFound() {
        when(tournaments.findById("t1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(identity("u1", "USER"), "t1"))
                .isInstanceOf(GameException.class)
                .hasMessage("赛事不存在");
    }

    private GameIdentity identity(String userId, String role) {
        long accountId = Long.parseLong(userId.substring(1));
        return new GameIdentity(accountId, userId, "用户" + accountId, role);
    }

    private TournamentRecord tournament(String status, String creatorUserId) {
        return new TournamentRecord(
                "t1", "演练", "heatmap-gomoku", "TOURNAMENT", "BO1", "TBD", status,
                creatorUserId, "创建者", 2, 0L, 0, 0, null, null,
                1_000L, null, null);
    }
}
