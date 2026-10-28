package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.converter.TournamentConverters;
import com.fishmatrix.game.entity.GameMatchEntity;
import com.fishmatrix.game.entity.GameTournamentEntity;
import com.fishmatrix.game.entity.GameTournamentPlayerEntity;
import com.fishmatrix.game.mapper.MatchMapper;
import com.fishmatrix.game.mapper.PlayerStatMapper;
import com.fishmatrix.game.mapper.TournamentMapper;
import com.fishmatrix.game.model.CreateDuelRequest;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.TournamentPlayerRecord;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.model.TournamentView;
import com.fishmatrix.game.ws.GameSession;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TournamentServiceDuelTest {

    private TournamentMapper tournaments;
    private MatchMapper matches;
    private GameSessionRegistry registry;
    private TournamentService service;

    @BeforeEach
    void setUp() {
        tournaments = mock(TournamentMapper.class);
        matches = mock(MatchMapper.class);
        registry = mock(GameSessionRegistry.class);
        service = new TournamentService(
                tournaments,
                matches,
                mock(PlayerStatMapper.class),
                registry,
                mock(GameNotificationService.class),
                new GameProperties(2, 16, 300, 60, 30, 60, 60, 600, 1000, "*"));
        when(tournaments.selectPlayers(any())).thenReturn(List.of());
        when(matches.selectByTournament(any())).thenReturn(List.of());
    }

    @Test
    void creatorInvitesOnlineOpponent() {
        when(registry.byUser("u2")).thenReturn(List.of(mock(GameSession.class)));

        TournamentView view = service.createDuel(
                identity("u1"), new CreateDuelRequest("u2", "乙", "bo3"));

        assertThat(view.kind()).isEqualTo(TournamentService.KIND_DUEL);
        assertThat(view.status()).isEqualTo("INVITING");
        assertThat(view.format()).isEqualTo("BO3");
        assertThat(view.targetPlayers()).isEqualTo(2);

        ArgumentCaptor<GameTournamentEntity> captor =
                ArgumentCaptor.forClass(GameTournamentEntity.class);
        verify(tournaments).insert(captor.capture());
        TournamentRecord duel = TournamentConverters.toRecord(captor.getValue());
        assertThat(duel.kind()).isEqualTo(TournamentService.KIND_DUEL);
        assertThat(duel.targetPlayers()).isEqualTo(2);

        ArgumentCaptor<GameTournamentPlayerEntity> players =
                ArgumentCaptor.forClass(GameTournamentPlayerEntity.class);
        verify(tournaments, times(2)).insertPlayer(players.capture());
        assertThat(players.getAllValues().stream().map(TournamentConverters::toRecord).toList())
                .extracting(TournamentPlayerRecord::userId, TournamentPlayerRecord::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("u1", "ACCEPTED"),
                        org.assertj.core.groups.Tuple.tuple("u2", "INVITED"));

        verify(registry).broadcastUsers(eq(Set.of("u2")), any());
        verify(registry).broadcastUsers(eq(Set.of("u1")), any());
        verify(registry, never()).broadcastAll(any());
    }

    @Test
    void offlineOpponentCannotBeInvited() {
        when(registry.byUser("u2")).thenReturn(List.of());

        assertThatThrownBy(() -> service.createDuel(
                identity("u1"), new CreateDuelRequest("u2", "乙", "BO1")))
                .isInstanceOf(GameException.class)
                .hasMessage("对方当前不在线，无法发起约战");

        verify(tournaments, never()).insert(any(GameTournamentEntity.class));
        verify(registry, never()).broadcastUsers(any(), any());
    }

    @Test
    void cannotChallengeSelf() {
        assertThatThrownBy(() -> service.createDuel(
                identity("u1"), new CreateDuelRequest("u1", "甲", "BO1")))
                .isInstanceOf(GameException.class)
                .hasMessage("不能和自己约战");
    }

    @Test
    void formatMustBeBestOf() {
        when(registry.byUser("u2")).thenReturn(List.of(mock(GameSession.class)));

        assertThatThrownBy(() -> service.createDuel(
                identity("u1"), new CreateDuelRequest("u2", "乙", "BO2")))
                .isInstanceOf(GameException.class)
                .hasMessage("赛制只支持 BO1 / BO3 / BO5");
    }

    @Test
    void duplicateActiveDuelIsRejected() {
        when(registry.byUser("u2")).thenReturn(List.of(mock(GameSession.class)));
        when(tournaments.existsActiveDuelBetween("u1", "u2", "heatmap-gomoku"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.createDuel(
                identity("u1"), new CreateDuelRequest("u2", "乙", "BO1")))
                .isInstanceOf(GameException.class)
                .hasMessage("你们在这个游戏下已有一场未结束的约战");
    }

    @Test
    void acceptingDuelStartsOneMatchAndOnlyNotifiesPlayers() {
        long now = 1_000L;
        TournamentRecord inviting = record("INVITING", 0);
        TournamentRecord running = record("RUNNING", 1);
        List<TournamentPlayerRecord> players = List.of(
                player("u1", "甲", 1, "ACCEPTED", now),
                player("u2", "乙", 2, "ACCEPTED", now));
        when(tournaments.selectActiveById("d1")).thenReturn(
                TournamentConverters.toEntity(inviting),
                TournamentConverters.toEntity(running));
        when(tournaments.accept(eq("d1"), eq("u2"), any(), any())).thenReturn(1);
        when(tournaments.selectAcceptedOrInvited("d1"))
                .thenReturn(TournamentConverters.playerEntities(players));
        when(tournaments.selectAcceptedPlayers("d1"))
                .thenReturn(TournamentConverters.playerEntities(players));
        when(tournaments.start(eq("d1"), any(), anyInt(), any())).thenReturn(1);
        when(tournaments.selectPlayers("d1"))
                .thenReturn(TournamentConverters.playerEntities(players));

        TournamentView view = service.acceptDuel(identity("u2"), "d1");

        assertThat(view.status()).isEqualTo("RUNNING");
        verify(matches).insert(any(GameMatchEntity.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> frame = ArgumentCaptor.forClass(Map.class);
        verify(registry).broadcastUsers(eq(Set.of("u1", "u2")), frame.capture());
        assertThat(frame.getValue()).containsEntry("type", "duel_started");
        verify(registry, never()).broadcastAll(any());
    }

    @Test
    void duelDetailIsVisibleOnlyToParticipants() {
        TournamentRecord duel = record("INVITING", 0);
        when(tournaments.selectActiveById("d1"))
                .thenReturn(TournamentConverters.toEntity(duel));
        when(tournaments.selectPlayer("d1", "u1")).thenReturn(
                TournamentConverters.toEntity(player("u1", "甲", 1, "ACCEPTED", 1_000L)));

        assertThat(service.getDuel(identity("u1"), "d1").kind())
                .isEqualTo(TournamentService.KIND_DUEL);
        assertThatThrownBy(() -> service.getDuel(identity("u3"), "d1"))
                .isInstanceOf(GameException.class)
                .hasMessage("约战不存在");
        assertThatThrownBy(() -> service.getTournament("d1"))
                .isInstanceOf(GameException.class)
                .hasMessage("赛事不存在");
    }

    private GameIdentity identity(String userId) {
        long accountId = Long.parseLong(userId.substring(1));
        return new GameIdentity(accountId, userId, "用户" + accountId, "USER");
    }

    private TournamentRecord record(String status, int totalRounds) {
        return new TournamentRecord(
                "d1", "一对一约战", "heatmap-gomoku", TournamentService.KIND_DUEL,
                "BO3", "TBD", status, "u1", "甲", 2, 10_000L,
                0, totalRounds, null, null, 1_000L, null, null);
    }

    private TournamentPlayerRecord player(String userId, String nickname, int seed,
                                          String status, long invitedAt) {
        return new TournamentPlayerRecord(
                "d1", userId, nickname, seed, status, invitedAt, invitedAt);
    }
}
