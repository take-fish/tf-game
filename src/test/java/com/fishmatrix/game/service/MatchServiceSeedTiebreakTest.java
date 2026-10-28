package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fishmatrix.game.arena.ArenaRoundFinishedEvent;
import com.fishmatrix.game.arena.ArenaService;
import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.converter.MatchConverters;
import com.fishmatrix.game.converter.TournamentConverters;
import com.fishmatrix.game.mapper.GameBlindPieceMapper;
import com.fishmatrix.game.mapper.MatchMapper;
import com.fishmatrix.game.mapper.TournamentMapper;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.RoundRecord;
import com.fishmatrix.game.model.TournamentPlayerRecord;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class MatchServiceSeedTiebreakTest {

    private MatchService service;
    private MatchMapper matches;
    private TournamentMapper tournaments;
    private GameSessionRegistry registry;

    @BeforeEach
    void setUp() {
        matches = mock(MatchMapper.class);
        tournaments = mock(TournamentMapper.class);
        registry = mock(GameSessionRegistry.class);
        service = new MatchService(
                matches,
                tournaments,
                mock(GameBlindPieceMapper.class),
                registry,
                mock(GameNotificationService.class),
                mock(ApplicationEventPublisher.class),
                new GameProperties(2, 16, 300, 60, 30, 60, 60, 600, 1000, "*"),
                mock(ArenaService.class),
                mock(ExerciseService.class));
    }

    @Test
    void drawInTournamentAwardsHigherSeed() {
        MatchRecord match = runningMatch();
        RoundRecord round = round();
        when(matches.selectById("m1")).thenReturn(MatchConverters.toEntity(match));
        when(matches.selectRound("r1")).thenReturn(MatchConverters.toEntity(round));
        when(matches.selectLatestRound("m1")).thenReturn(MatchConverters.toEntity(round));
        when(matches.selectMoves("r1")).thenReturn(List.of());
        when(tournaments.selectActiveById("t1"))
                .thenReturn(TournamentConverters.toEntity(tournament()));
        when(tournaments.selectPlayer("t1", "u3"))
                .thenReturn(TournamentConverters.toEntity(player("u3", 3)));
        when(tournaments.selectPlayer("t1", "u4"))
                .thenReturn(TournamentConverters.toEntity(player("u4", 4)));
        when(matches.finishIfRunning(eq("m1"), eq("u3"), anyInt(), anyInt(), any()))
                .thenReturn(1);

        service.onArenaRoundFinished(new ArenaRoundFinishedEvent("m1", "r1", null, "和棋"));

        verify(matches).finishIfRunning(eq("m1"), eq("u3"), eq(1), eq(0), any());
        verify(matches).finishRoundIfRunning(eq("r1"), eq("u3"), any());
        // 一帧 match_update 刷新视图，一帧 match_result 通知结算。
        verify(registry, times(2)).broadcastMatch(eq("m1"), any(), any());
    }

    @Test
    void drawStillFavoursLowerSeedNumber() {
        MatchRecord match = new MatchRecord(
                "m1", "t1", 3, 1, "PAGE_PLAYOFF", "RUNNING",
                "u4", "丁", "u3", "丙", 0, 0, null, null,
                1_000L, 2_000L, null, "heatmap-gomoku", "u3");
        RoundRecord round = round();
        when(matches.selectById("m1")).thenReturn(MatchConverters.toEntity(match));
        when(matches.selectRound("r1")).thenReturn(MatchConverters.toEntity(round));
        when(matches.selectLatestRound("m1")).thenReturn(MatchConverters.toEntity(round));
        when(matches.selectMoves("r1")).thenReturn(List.of());
        when(tournaments.selectActiveById("t1"))
                .thenReturn(TournamentConverters.toEntity(tournament()));
        when(tournaments.selectPlayer("t1", "u4"))
                .thenReturn(TournamentConverters.toEntity(player("u4", 4)));
        when(tournaments.selectPlayer("t1", "u3"))
                .thenReturn(TournamentConverters.toEntity(player("u3", 3)));
        when(matches.finishIfRunning(eq("m1"), eq("u3"), anyInt(), anyInt(), any()))
                .thenReturn(1);

        service.onArenaRoundFinished(new ArenaRoundFinishedEvent("m1", "r1", null, "和棋"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> frame = ArgumentCaptor.forClass(Map.class);
        // 第二次广播是 match_result，最后一个捕获值即结算帧。
        verify(registry, times(2)).broadcastMatch(eq("m1"), any(), frame.capture());
        assertThat(frame.getValue())
                .containsEntry("winnerUserId", "u3")
                .containsEntry("player1Score", 0)
                .containsEntry("player2Score", 1);
        assertThat(frame.getValue().get("reason").toString()).contains("高顺位晋级");
    }

    private MatchRecord runningMatch() {
        return new MatchRecord(
                "m1", "t1", 3, 1, "PAGE_PLAYOFF", "RUNNING",
                "u3", "丙", "u4", "丁", 0, 0, null, null,
                1_000L, 2_000L, null, "heatmap-gomoku", "u3");
    }

    private RoundRecord round() {
        return new RoundRecord(
                "r1", "m1", "heatmap-gomoku", 1, "RUNNING",
                "u3", "u4", null, "u3", null, 0, 2_000L,
                null, "", null, 0, 2_000L, null, null);
    }

    private TournamentRecord tournament() {
        return new TournamentRecord(
                "t1", "演练", "random", "TOURNAMENT", "BO1", "PAGE_PLAYOFF",
                "RUNNING", "u1", "甲", 4, 10_000L, 3, 5,
                null, null, null, null, 1_000L, 2_000L, null, null, null);
    }

    private TournamentPlayerRecord player(String userId, int seed) {
        return new TournamentPlayerRecord(
                "t1", userId, "选手" + seed, seed, "ACCEPTED", 1_000L, 1_000L);
    }
}
