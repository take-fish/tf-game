package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fishmatrix.game.arena.ArenaService;
import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.converter.MatchConverters;
import com.fishmatrix.game.converter.TournamentConverters;
import com.fishmatrix.game.entity.GameRoundEntity;
import com.fishmatrix.game.mapper.GameBlindPieceMapper;
import com.fishmatrix.game.mapper.MatchMapper;
import com.fishmatrix.game.mapper.TournamentMapper;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.RoundRecord;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 覆盖两处回归：
 * 1. 容量编排小局没有 turn_user_id，必须靠 ends_at 单独巡检并超时收口；
 * 2. S赛后续小局抽签必须落在赛事启用的 game_pool 内。
 */
class MatchServiceDrillTickTest {

    private static final long NOW = 1_000_000L;

    private MatchService service;
    private MatchMapper matches;
    private TournamentMapper tournaments;

    @BeforeEach
    void setUp() {
        matches = mock(MatchMapper.class);
        tournaments = mock(TournamentMapper.class);
        GameSessionRegistry registry = mock(GameSessionRegistry.class);
        when(matches.selectDueRest(any())).thenReturn(List.of());
        when(matches.selectRunningRounds()).thenReturn(List.of());
        when(matches.selectExpiredDrillRounds(any())).thenReturn(List.of());
        when(matches.selectMoves(anyString())).thenReturn(List.of());
        when(registry.byUser(anyString())).thenReturn(List.of());

        service = new MatchService(
                matches, tournaments, mock(GameBlindPieceMapper.class),
                registry, mock(GameNotificationService.class),
                mock(ApplicationEventPublisher.class),
                new GameProperties(2, 16, 300, 60, 30, 60, 60, 600, 1000, "*"),
                mock(ArenaService.class), mock(ExerciseService.class));
    }

    @Test
    void tickSettlesExpiredCapacityDrillRounds() {
        MatchRecord match = new MatchRecord(
                "m1", "t1", 1, 1, "KNOCKOUT", "RUNNING",
                "u1", "甲", "u2", "乙", 0, 0, null, null,
                NOW, NOW, null, "capacity-drill", null);
        RoundRecord drillRound = new RoundRecord(
                "r1", "m1", "capacity-drill", 1, "RUNNING",
                "u1", "u2", null, null, null, 0, NOW, null, "",
                null, 0, NOW, NOW - 1, null);
        GameRoundEntity roundEntity = MatchConverters.toEntity(drillRound);

        when(matches.selectById("m1")).thenReturn(MatchConverters.toEntity(match));
        when(matches.selectExpiredDrillRounds(any())).thenReturn(List.of(roundEntity));
        when(matches.selectRoundForUpdate("r1")).thenReturn(roundEntity);
        when(matches.selectLatestRound("m1")).thenReturn(roundEntity);
        when(matches.finishIfRunning(anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn(1);
        when(matches.finishRoundIfRunning(anyString(), any(), any())).thenReturn(1);

        service.tick();

        verify(matches).selectExpiredDrillRounds(any());
        // 双方都没提交：直接超时收口，BO1 按高顺位（player1）晋级。
        verify(matches).finishIfRunning(eq("m1"), eq("u1"), eq(0), eq(0), any());
        verify(matches).finishRoundIfRunning(eq("r1"), isNull(), any());
    }

    @Test
    void nextRoundDrawsOnlyFromEnabledGamePool() {
        MatchRecord match = new MatchRecord(
                "m1", "t1", 2, 1, "KNOCKOUT", "RUNNING",
                "u1", "甲", "u2", "乙", 1, 0, null, null,
                NOW, NOW, null, null, null);
        RoundRecord previous = new RoundRecord(
                "r1", "m1", "heatmap-gomoku", 1, "REST",
                "u1", "u2", null, null, "u1", 0, NOW, NOW + 60_000L, "",
                null, 0, NOW, null, null);
        TournamentRecord series = new TournamentRecord(
                "t1", "S赛", "random", "TOURNAMENT", "BO3", "KNOCKOUT",
                "RUNNING", "u1", "甲", 2, 3, 1, 1,
                null, null, null, null, NOW, NOW, null, null,
                List.of("log-hotspot-arena", "topology-arena"));

        when(matches.selectById("m1")).thenReturn(MatchConverters.toEntity(match));
        when(matches.selectLatestRound("m1")).thenReturn(MatchConverters.toEntity(previous));
        when(matches.finishRestRound(eq("r1"), any())).thenReturn(1);
        when(tournaments.selectActiveById("t1"))
                .thenReturn(TournamentConverters.toEntity(series));

        service.startNextGame("m1");

        ArgumentCaptor<GameRoundEntity> captor = ArgumentCaptor.forClass(GameRoundEntity.class);
        verify(matches).insertRound(captor.capture());
        assertThat(captor.getValue().getGameId())
                .isIn("log-hotspot-arena", "topology-arena");
    }
}
