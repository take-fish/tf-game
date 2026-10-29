package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fishmatrix.game.arena.ArenaGames;
import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.converter.MatchConverters;
import com.fishmatrix.game.converter.TournamentConverters;
import com.fishmatrix.game.mapper.MatchMapper;
import com.fishmatrix.game.mapper.PlayerStatMapper;
import com.fishmatrix.game.mapper.TournamentMapper;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchFinishedEvent;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TournamentServicePagePlayoffTest {

    private TournamentMapper tournaments;
    private MatchMapper matches;
    private PlayerStatMapper stats;
    private GameSessionRegistry registry;
    private TournamentService service;

    @BeforeEach
    void setUp() {
        tournaments = mock(TournamentMapper.class);
        matches = mock(MatchMapper.class);
        stats = mock(PlayerStatMapper.class);
        registry = mock(GameSessionRegistry.class);
        service = new TournamentService(
                tournaments,
                matches,
                stats,
                registry,
                mock(GameNotificationService.class),
                new GameProperties(2, 16, 300, 60, 30, 60, 60, 600, 1000, "*"));
        when(tournaments.selectPlayers(any())).thenReturn(List.of());
        when(matches.selectByTournament(any())).thenReturn(List.of());
    }

    @Test
    void chooserCanPickFirstGameBeforeStart() {
        MatchRecord pending = playoffMatch("m1", "READY", "u3");
        MatchRecord updated = playoffMatch("m1", "READY", "u3", "topology-arena");
        when(matches.selectById("m1")).thenReturn(
                MatchConverters.toEntity(pending), MatchConverters.toEntity(updated));
        when(tournaments.selectActiveById("t1"))
                .thenReturn(TournamentConverters.toEntity(tournament("RUNNING")));
        when(matches.updateFirstGame("m1", "topology-arena")).thenReturn(1);

        MatchView view = service.chooseGame(identity("u3"), "m1", "topology-arena");

        verify(matches).updateFirstGame("m1", "topology-arena");
        verify(registry).broadcastAll(any());
        assertThat(view.firstGameId()).isEqualTo("topology-arena");
        assertThat(view.gameChooserUserId()).isEqualTo("u3");
    }

    @Test
    void randomChoiceResolvesToSupportedScript() {
        MatchRecord pending = playoffMatch("m1", "READY", "u3");
        when(matches.selectById("m1")).thenReturn(
                MatchConverters.toEntity(pending), MatchConverters.toEntity(pending));
        when(tournaments.selectActiveById("t1"))
                .thenReturn(TournamentConverters.toEntity(tournament("RUNNING")));
        when(matches.updateFirstGame(eq("m1"), any())).thenReturn(1);

        service.chooseGame(identity("u3"), "m1", "random");

        ArgumentCaptor<String> gameId = ArgumentCaptor.forClass(String.class);
        verify(matches).updateFirstGame(eq("m1"), gameId.capture());
        assertThat(ArenaGames.TOURNAMENT_POOL).contains(gameId.getValue());
    }

    @Test
    void lowerSeedCannotPickFirstGame() {
        when(matches.selectById("m1"))
                .thenReturn(MatchConverters.toEntity(playoffMatch("m1", "READY", "u3")));
        when(tournaments.selectActiveById("t1"))
                .thenReturn(TournamentConverters.toEntity(tournament("RUNNING")));

        assertThatThrownBy(() -> service.chooseGame(identity("u4"), "m1", "topology-arena"))
                .isInstanceOf(GameException.class)
                .hasMessage("只有高顺位选手拥有本场首局剧本选择权");
        verify(matches, never()).updateFirstGame(any(), any());
    }

    @Test
    void cannotChangeGameAfterMatchStarts() {
        MatchRecord started = new MatchRecord(
                "m1", "t1", 3, 1, "PAGE_PLAYOFF", "RUNNING",
                "u3", "丙", "u4", "丁", 0, 0, null, null,
                1_000L, 2_000L, null, "heatmap-gomoku", "u3");
        when(matches.selectById("m1")).thenReturn(MatchConverters.toEntity(started));
        when(tournaments.selectActiveById("t1"))
                .thenReturn(TournamentConverters.toEntity(tournament("RUNNING")));

        assertThatThrownBy(() -> service.chooseGame(identity("u3"), "m1", "topology-arena"))
                .isInstanceOf(GameException.class)
                .hasMessage("比赛已开始，不能改选剧本");
    }

    @Test
    void pagePlayoffFinalAwardsChampionAndRunnerUp() {
        MatchRecord finalMatch = new MatchRecord(
                "m-final", "t1", 5, 1, "PAGE_PLAYOFF", "FINISHED",
                "u1", "甲", "u2", "乙", 0, 1, "u2", null,
                1_000L, 2_000L, 3_000L, "heatmap-gomoku", "u1");
        TournamentRecord running = tournament("RUNNING");
        TournamentRecord finished = new TournamentRecord(
                "t1", "演练", "random", "TOURNAMENT", "BO1", "PAGE_PLAYOFF",
                "FINISHED", "u1", "甲", 4, 10_000L, 5, 5,
                "u2", "乙", "u1", "甲", 1_000L, 2_000L, 3_000L, null, null);
        when(matches.selectById("m-final"))
                .thenReturn(MatchConverters.toEntity(finalMatch));
        when(tournaments.selectActiveById("t1")).thenReturn(
                TournamentConverters.toEntity(running),
                TournamentConverters.toEntity(finished));

        service.onMatchFinished(new MatchFinishedEvent("m-final"));

        verify(tournaments).finish(
                eq("t1"), eq("u2"), eq("乙"), eq("u1"), eq("甲"), any());
        verify(stats).addTitle("u2", "乙", 1, 0, PlayerStatRules.CHAMPION_POINTS);
        verify(stats).addTitle("u1", "甲", 0, 1, PlayerStatRules.RUNNER_UP_POINTS);
    }

    private GameIdentity identity(String userId) {
        long accountId = Long.parseLong(userId.substring(1));
        return new GameIdentity(accountId, userId, "用户" + accountId, "USER");
    }

    private MatchRecord playoffMatch(String id, String status, String chooser) {
        return playoffMatch(id, status, chooser, "heatmap-gomoku");
    }

    private MatchRecord playoffMatch(String id, String status, String chooser,
                                     String firstGameId) {
        return new MatchRecord(
                id, "t1", 3, 1, "PAGE_PLAYOFF", status,
                "u3", "丙", "u4", "丁", 0, 0, null, null,
                1_000L, null, null, firstGameId, chooser);
    }

    private TournamentRecord tournament(String status) {
        return new TournamentRecord(
                "t1", "演练", "random", "TOURNAMENT", "BO1", "PAGE_PLAYOFF",
                status, "u1", "甲", 4, 10_000L, 3, 5,
                null, null, null, null, 1_000L, 2_000L, null, null, null);
    }
}
