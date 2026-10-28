package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.converter.MatchConverters;
import com.fishmatrix.game.converter.TournamentConverters;
import com.fishmatrix.game.entity.GameTournamentEntity;
import com.fishmatrix.game.mapper.MatchMapper;
import com.fishmatrix.game.mapper.PlayerStatMapper;
import com.fishmatrix.game.mapper.TournamentMapper;
import com.fishmatrix.game.model.CreateTournamentRequest;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.model.PlayerRef;
import com.fishmatrix.game.model.TournamentPlayerRecord;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.model.TournamentView;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TournamentServiceGamePoolTest {

    private TournamentMapper tournaments;
    private MatchMapper matches;
    private TournamentService service;

    @BeforeEach
    void setUp() {
        tournaments = mock(TournamentMapper.class);
        matches = mock(MatchMapper.class);
        service = new TournamentService(
                tournaments,
                matches,
                mock(PlayerStatMapper.class),
                mock(GameSessionRegistry.class),
                mock(GameNotificationService.class),
                new GameProperties(2, 16, 300, 60, 30, 60, 60, 600, 1000, "*"));
        when(tournaments.selectPlayers(any())).thenReturn(List.of());
        when(matches.selectByTournament(any())).thenReturn(List.of());
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
        verify(tournaments, never()).insert(any(GameTournamentEntity.class));
    }

    @Test
    void seriesRejectsUnknownGameInPool() {
        assertThatThrownBy(() -> service.create(identity("u1"), new CreateTournamentRequest(
                null, "BO1", players(), "random", null, List.of("unknown-game"))))
                .isInstanceOf(GameException.class)
                .hasMessage("随机池包含不支持的游戏");
        verify(tournaments, never()).insert(any(GameTournamentEntity.class));
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
        List<TournamentPlayerRecord> players = List.of(player("u1", 1), player("u2", 2));
        when(tournaments.selectAcceptedOrInvited("t1"))
                .thenReturn(TournamentConverters.playerEntities(players));
        when(tournaments.selectAcceptedPlayers("t1"))
                .thenReturn(TournamentConverters.playerEntities(players));
        when(tournaments.start(eq("t1"), anyString(), anyInt(), any())).thenReturn(1);
        when(tournaments.selectActiveById("t1"))
                .thenReturn(TournamentConverters.toEntity(series));

        service.startIfReady(series);

        verify(matches).initFirstGame(anyString(), eq("log-hotspot-arena"), any());
    }

    @Test
    void firstGameChooserCannotEscapeEnabledPool() {
        TournamentRecord series = series("RUNNING", List.of("log-hotspot-arena"), null);
        when(tournaments.selectActiveById("t1"))
                .thenReturn(TournamentConverters.toEntity(series));
        when(matches.selectById("m1"))
                .thenReturn(MatchConverters.toEntity(readyMatch()));

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
        when(tournaments.selectActiveById("t1"))
                .thenReturn(TournamentConverters.toEntity(series));
        when(matches.selectById("m1")).thenReturn(
                MatchConverters.toEntity(readyMatch()),
                MatchConverters.toEntity(updated));
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
