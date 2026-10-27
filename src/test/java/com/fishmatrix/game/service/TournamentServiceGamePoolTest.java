package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.model.CreateTournamentRequest;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.model.PlayerRef;
import com.fishmatrix.game.model.TournamentPlayerRecord;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.model.TournamentView;
import com.fishmatrix.game.store.MatchRepository;
import com.fishmatrix.game.store.PlayerStatRepository;
import com.fishmatrix.game.store.TournamentRepository;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TournamentServiceGamePoolTest {

    private TournamentRepository tournaments;
    private MatchRepository matches;
    private TournamentService service;

    @BeforeEach
    void setUp() {
        tournaments = mock(TournamentRepository.class);
        matches = mock(MatchRepository.class);
        service = new TournamentService(
                tournaments,
                matches,
                mock(PlayerStatRepository.class),
                mock(GameSessionRegistry.class),
                mock(GameNotificationService.class),
                new GameProperties(2, 16, 300, 60, 30, 60, 60, 600, 1000, "*"));
        when(tournaments.listPlayers(any())).thenReturn(List.of());
        when(matches.listByTournament(any())).thenReturn(List.of());
    }

    @Test
    void seriesDefaultsToAllFiveOneVersusOneGames() {
        TournamentView view = service.create(identity("u1"), new CreateTournamentRequest(
                null, "BO1", players(), "random", null, null));

        assertThat(view.gameId()).isEqualTo("random");
        assertThat(view.name()).isEqualTo("系统随机演练");
        assertThat(view.gamePool()).containsExactly(
                "heatmap-gomoku", "log-hotspot-arena", "topology-arena",
                "cluster-patrol-arena", "blind-xiangqi");
        // 启用盲棋时缺省使用标准点位摆法。
        assertThat(view.blindLayout()).isEqualTo("STANDARD_SHUFFLE");
    }

    @Test
    void seriesKeepsSelectedPoolAndSkipsBlindLayoutWhenBlindDisabled() {
        TournamentView view = service.create(identity("u1"), new CreateTournamentRequest(
                null, "BO3", players(), "random", null,
                List.of("log-hotspot-arena", "topology-arena")));

        assertThat(view.gamePool()).containsExactly("log-hotspot-arena", "topology-arena");
        assertThat(view.blindLayout()).isNull();
    }

    @Test
    void seriesWithoutAnyEnabledGameIsRejected() {
        assertThatThrownBy(() -> service.create(identity("u1"), new CreateTournamentRequest(
                null, "BO1", players(), "random", null, List.of())))
                .isInstanceOf(GameException.class)
                .hasMessage("S赛至少需要启用一个游戏");
        verify(tournaments, never()).insert(any());
    }

    @Test
    void seriesRejectsUnknownGameInPool() {
        assertThatThrownBy(() -> service.create(identity("u1"), new CreateTournamentRequest(
                null, "BO1", players(), "random", null, List.of("unknown-game"))))
                .isInstanceOf(GameException.class)
                .hasMessage("随机池包含不支持的游戏");
        verify(tournaments, never()).insert(any());
    }

    @Test
    void championshipDoesNotStoreRandomPool() {
        TournamentView view = service.create(identity("u1"), new CreateTournamentRequest(
                null, "BO1", players(), "topology-arena", null,
                List.of("heatmap-gomoku")));

        assertThat(view.gameId()).isEqualTo("topology-arena");
        assertThat(view.gamePool()).isEmpty();
        assertThat(view.blindLayout()).isNull();
    }

    @Test
    void startDrawsFirstGameOnlyFromEnabledPool() {
        TournamentRecord series = series("INVITING", List.of("log-hotspot-arena"), null);
        when(tournaments.allAcceptedOrInvited("t1")).thenReturn(List.of(
                player("u1", 1), player("u2", 2)));
        when(tournaments.acceptedPlayers("t1")).thenReturn(List.of(
                player("u1", 1), player("u2", 2)));
        when(tournaments.start(eq("t1"), anyString(), anyInt(), anyLong())).thenReturn(1);
        when(tournaments.findById("t1")).thenReturn(Optional.of(series));

        service.startIfReady(series);

        verify(matches).initFirstGame(anyString(), eq("log-hotspot-arena"), any());
    }

    @Test
    void firstGameChooserCannotEscapeEnabledPool() {
        TournamentRecord series = series("RUNNING", List.of("log-hotspot-arena"), null);
        when(tournaments.findById("t1")).thenReturn(Optional.of(series));
        when(matches.findById("m1")).thenReturn(Optional.of(readyMatch()));

        assertThatThrownBy(() -> service.chooseGame(
                identity("u1"), "m1", "heatmap-gomoku"))
                .isInstanceOf(GameException.class)
                .hasMessage("该游戏未在 S赛启用池中");
        verify(matches, never()).updateFirstGame(any(), any());
    }

    @Test
    void firstGameChooserRandomOptionUsesEnabledPool() {
        TournamentRecord series = series("RUNNING", List.of("log-hotspot-arena"), null);
        MatchRecord updated = new MatchRecord(
                "m1", "t1", 1, 1, "KNOCKOUT", "READY",
                "u1", "甲", "u2", "乙", 0, 0, null, null,
                1_000L, null, null, "log-hotspot-arena", "u1");
        when(tournaments.findById("t1")).thenReturn(Optional.of(series));
        when(matches.findById("m1"))
                .thenReturn(Optional.of(readyMatch()), Optional.of(updated));
        when(matches.updateFirstGame("m1", "log-hotspot-arena")).thenReturn(1);

        MatchView view = service.chooseGame(identity("u1"), "m1", "random");

        assertThat(view.firstGameId()).isEqualTo("log-hotspot-arena");
        verify(matches).updateFirstGame("m1", "log-hotspot-arena");
    }

    private static TournamentRecord series(String status, List<String> pool, String blindLayout) {
        return new TournamentRecord(
                "t1", "S赛", "random", TournamentService.KIND_TOURNAMENT, "BO1", "TBD",
                status, "u1", "甲", 2, 10_000L, 1, 1, null, null,
                null, null, 1_000L, 2_000L, null, blindLayout, pool);
    }

    private static MatchRecord readyMatch() {
        return new MatchRecord(
                "m1", "t1", 1, 1, "KNOCKOUT", "READY",
                "u1", "甲", "u2", "乙", 0, 0, null, null,
                1_000L, null, null, null, "u1");
    }

    private static TournamentPlayerRecord player(String userId, int seed) {
        return new TournamentPlayerRecord(
                "t1", userId, "选手" + seed, seed, "ACCEPTED", 1_000L, 1_000L);
    }

    private static List<PlayerRef> players() {
        return List.of(new PlayerRef("u1", "甲"), new PlayerRef("u2", "乙"));
    }

    private static GameIdentity identity(String userId) {
        long accountId = Long.parseLong(userId.substring(1));
        return new GameIdentity(accountId, userId, "用户" + accountId, "USER");
    }
}
