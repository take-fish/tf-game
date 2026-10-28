package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.RoundRecord;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class MatchServiceExerciseTest {

    private MatchService service;
    private MatchMapper matches;
    private ExerciseService exercise;
    private GameSessionRegistry registry;

    @BeforeEach
    void setUp() {
        matches = mock(MatchMapper.class);
        TournamentMapper tournaments = mock(TournamentMapper.class);
        registry = mock(GameSessionRegistry.class);
        exercise = mock(ExerciseService.class);
        when(exercise.supports("cluster-patrol-arena")).thenReturn(true);
        when(exercise.startTournament(anyString(), anyString(), any(), anyLong()))
                .thenReturn(new ExerciseService.StartState(
                        "{\"gameId\":\"cluster-patrol-arena\",\"seq\":0}", 5_000L));
        when(matches.start(eq("m1"), any())).thenReturn(1);
        when(matches.selectById("m1")).thenReturn(MatchConverters.toEntity(match()));
        when(matches.selectLatestRound("m1")).thenReturn(null);
        when(tournaments.selectActiveById("t1"))
                .thenReturn(TournamentConverters.toEntity(tournament()));
        when(registry.byUser(anyString())).thenReturn(List.of());
        service = new MatchService(
                matches, tournaments, mock(GameBlindPieceMapper.class),
                registry, mock(GameNotificationService.class),
                mock(ApplicationEventPublisher.class),
                new GameProperties(2, 16, 300, 60, 30, 60, 60, 600, 1000, "*"),
                mock(ArenaService.class), exercise);
    }

    @Test
    void firstGameStartsAClusterPatrolExerciseRound() {
        service.ready(identity("u1"), "m1");
        service.ready(identity("u2"), "m1");

        ArgumentCaptor<GameRoundEntity> captor = ArgumentCaptor.forClass(GameRoundEntity.class);
        verify(matches).insertRound(captor.capture());
        RoundRecord round = MatchConverters.toRecord(captor.getValue());

        assertThat(round.gameId()).isEqualTo("cluster-patrol-arena");
        assertThat(round.turnUserId()).isNull();
        assertThat(round.stateJson()).contains("cluster-patrol-arena");
        assertThat(round.endsAt()).isEqualTo(5_000L);
        verify(exercise).startTournament(eq("m1"), anyString(), any(), anyLong());
    }

    private MatchRecord match() {
        return new MatchRecord(
                "m1", "t1", 1, 1, "LEAGUE", "READY",
                "u1", "甲", "u2", "乙", 0, 0, null, null,
                1_000L, null, null, "cluster-patrol-arena", null);
    }

    private TournamentRecord tournament() {
        return new TournamentRecord(
                "t1", "巡检杯", "random", "TOURNAMENT", "BO1", "LEAGUE",
                "RUNNING", "u1", "甲", 4, 10_000L, 1, 1,
                null, null, null, null, 1_000L, 2_000L, null, null, null);
    }

    private static GameIdentity identity(String userId) {
        return new GameIdentity(1L, userId, "选手", "USER");
    }
}
