package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.model.CreateDuelRequest;
import com.fishmatrix.game.model.CreateTournamentRequest;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.PlayerRef;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.model.TournamentView;
import com.fishmatrix.game.store.MatchRepository;
import com.fishmatrix.game.store.PlayerStatRepository;
import com.fishmatrix.game.store.TournamentRepository;
import com.fishmatrix.game.ws.GameSession;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TournamentServiceBlindTest {

    private TournamentRepository tournaments;
    private GameSessionRegistry registry;
    private TournamentService service;

    @BeforeEach
    void setUp() {
        tournaments = mock(TournamentRepository.class);
        MatchRepository matches = mock(MatchRepository.class);
        registry = mock(GameSessionRegistry.class);
        service = new TournamentService(
                tournaments,
                matches,
                mock(PlayerStatRepository.class),
                registry,
                mock(GameNotificationService.class),
                new GameProperties(2, 16, 300, 60, 30, 60, 60, 600, 1000, "*"));
        when(tournaments.listPlayers(any())).thenReturn(List.of());
        when(matches.listByTournament(any())).thenReturn(List.of());
    }

    @Test
    void createsBlindTournamentWithSelectedLayout() {
        TournamentView view = service.create(identity("u1"), new CreateTournamentRequest(
                null, "BO1", players(), "blind-xiangqi", "FULL_RANDOM"));

        assertThat(view.gameId()).isEqualTo("blind-xiangqi");
        assertThat(view.blindLayout()).isEqualTo("FULL_RANDOM");
        assertThat(view.name()).isEqualTo("盲棋");

        ArgumentCaptor<TournamentRecord> captor = ArgumentCaptor.forClass(TournamentRecord.class);
        verify(tournaments).insert(captor.capture());
        assertThat(captor.getValue().gameId()).isEqualTo("blind-xiangqi");
        assertThat(captor.getValue().blindLayout()).isEqualTo("FULL_RANDOM");
        assertThat(captor.getValue().name()).isEqualTo("盲棋");
    }

    @Test
    void defaultsBlindLayoutToStandardShuffle() {
        service.create(identity("u1"), new CreateTournamentRequest(
                "自定义盲棋", "BO3", players(), "blind-xiangqi", null));

        ArgumentCaptor<TournamentRecord> captor = ArgumentCaptor.forClass(TournamentRecord.class);
        verify(tournaments).insert(captor.capture());
        assertThat(captor.getValue().blindLayout()).isEqualTo("STANDARD_SHUFFLE");
    }

    @Test
    void gomokuIgnoresBlindLayout() {
        service.create(identity("u1"), new CreateTournamentRequest(
                null, "BO1", players(), "heatmap-gomoku", "FULL_RANDOM"));

        ArgumentCaptor<TournamentRecord> captor = ArgumentCaptor.forClass(TournamentRecord.class);
        verify(tournaments).insert(captor.capture());
        assertThat(captor.getValue().gameId()).isEqualTo("heatmap-gomoku");
        assertThat(captor.getValue().blindLayout()).isNull();
        assertThat(captor.getValue().name()).isEqualTo("热力五子棋");
    }

    @Test
    void rejectsUnknownGameId() {
        assertThatThrownBy(() -> service.create(identity("u1"), new CreateTournamentRequest(
                null, "BO1", players(), "unknown-game", null)))
                .isInstanceOf(GameException.class)
                .hasMessage("不支持的游戏类型");
        verify(tournaments, never()).insert(any());
    }

    @Test
    void rejectsInvalidBlindLayout() {
        assertThatThrownBy(() -> service.create(identity("u1"), new CreateTournamentRequest(
                null, "BO1", players(), "blind-xiangqi", "NOT_A_LAYOUT")))
                .isInstanceOf(GameException.class)
                .hasMessage("盲棋摆法只支持 STANDARD_SHUFFLE / FULL_RANDOM / HALF_RANDOM");
        verify(tournaments, never()).insert(any());
    }

    @Test
    void createsBlindDuelWithSelectedLayout() {
        when(registry.byUser("u2")).thenReturn(List.of(mock(GameSession.class)));

        TournamentView view = service.createDuel(identity("u1"), new CreateDuelRequest(
                "u2", "乙", "BO1", "blind-xiangqi", "HALF_RANDOM"));

        assertThat(view.gameId()).isEqualTo("blind-xiangqi");
        assertThat(view.blindLayout()).isEqualTo("HALF_RANDOM");
        assertThat(view.name()).isEqualTo("盲棋约战");
        assertThat(view.kind()).isEqualTo(TournamentService.KIND_DUEL);

        ArgumentCaptor<TournamentRecord> captor = ArgumentCaptor.forClass(TournamentRecord.class);
        verify(tournaments).insert(captor.capture());
        assertThat(captor.getValue().gameId()).isEqualTo("blind-xiangqi");
        assertThat(captor.getValue().blindLayout()).isEqualTo("HALF_RANDOM");
        verify(tournaments, times(2)).insertPlayer(any());
    }

    @Test
    void blindDuelDefaultsLayoutToStandardShuffle() {
        when(registry.byUser("u2")).thenReturn(List.of(mock(GameSession.class)));

        service.createDuel(identity("u1"), new CreateDuelRequest(
                "u2", "乙", "BO3", "blind-xiangqi", null));

        ArgumentCaptor<TournamentRecord> captor = ArgumentCaptor.forClass(TournamentRecord.class);
        verify(tournaments).insert(captor.capture());
        assertThat(captor.getValue().blindLayout()).isEqualTo("STANDARD_SHUFFLE");
    }

    private static List<PlayerRef> players() {
        return List.of(new PlayerRef("u1", "甲"), new PlayerRef("u2", "乙"));
    }

    private static GameIdentity identity(String userId) {
        long accountId = Long.parseLong(userId.substring(1));
        return new GameIdentity(accountId, userId, "用户" + accountId, "USER");
    }
}
