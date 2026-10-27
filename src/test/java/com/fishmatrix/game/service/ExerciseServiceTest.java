package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.game.arena.ExerciseEngine;
import com.fishmatrix.game.arena.ExerciseInput;
import com.fishmatrix.game.config.ExerciseProperties;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.store.ExerciseRepository;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class ExerciseServiceTest {

    private ExerciseProperties properties;
    private ExerciseRepository repository;
    private ExerciseService service;

    @BeforeEach
    void setUp() {
        properties = new ExerciseProperties(true, 40, 80, 1, 180, 1, 4, 30, 380);
        repository = mock(ExerciseRepository.class);
        GameSessionRegistry registry = mock(GameSessionRegistry.class);
        when(registry.all()).thenReturn(List.of());
        service = new ExerciseService(
                new ExerciseEngine(properties), properties, repository, registry,
                mock(ApplicationEventPublisher.class), new ObjectMapper());
    }

    @Test
    void twoPlayersReadyStartTheRoom() {
        Map<String, Object> created = service.create(identity("u1", "甲"), "FFA", 2);
        String code = (String) created.get("roomCode");
        assertThat(created).containsEntry("status", "WAITING");

        service.join(identity("u2", "乙"), code);
        Map<String, Object> started = service.ready(identity("u2", "乙"), code);

        assertThat(started).containsEntry("status", "RUNNING");
        assertThat(started.get("state")).isNotNull();
        verify(repository).insertRoom(any());
        verify(repository, times(2)).insertMember(any());
        verify(repository).updateRoomState(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void trainingStartsImmediatelyWithThreeBots() {
        Map<String, Object> view = service.create(identity("u1", "甲"), "TRAINING", 1);

        assertThat(view).containsEntry("status", "RUNNING");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) view.get("members");
        assertThat(members).hasSize(4);
        assertThat(members.stream().filter(member -> Boolean.TRUE.equals(member.get("bot"))))
                .hasSize(3);
        verify(repository, times(4)).insertMember(any());
    }

    @Test
    void roomLimitAndDuplicateCreateAreRejected() {
        service.create(identity("u1", "甲"), "FFA", 4);

        assertThatThrownBy(() -> service.create(identity("u2", "乙"), "FFA", 4))
                .isInstanceOf(GameException.class)
                .hasMessageContaining("房间已满");
        assertThatThrownBy(() -> service.create(identity("u1", "甲"), "FFA", 4))
                .isInstanceOf(GameException.class)
                .hasMessageContaining("你已在一个巡检房间中");
    }

    @Test
    void inputIsRateLimitedPerSecond() {
        Map<String, Object> view = service.create(identity("u1", "甲"), "TRAINING", 1);
        String roomId = (String) view.get("roomId");

        for (int index = 0; index < 30; index += 1) {
            service.input(identity("u1", "甲"), roomId, ExerciseInput.empty());
        }
        assertThatThrownBy(() -> service.input(
                identity("u1", "甲"), roomId, ExerciseInput.empty()))
                .isInstanceOf(GameException.class)
                .hasMessageContaining("操作太频繁");
    }

    @Test
    void nonMemberCannotJoinTheTrainingRoom() {
        Map<String, Object> view = service.create(identity("u1", "甲"), "TRAINING", 1);
        String code = (String) view.get("roomCode");

        assertThatThrownBy(() -> service.join(identity("u2", "乙"), code))
                .isInstanceOf(GameException.class);
        verify(repository, never()).updateMemberReady(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    private static GameIdentity identity(String userId, String nickname) {
        return new GameIdentity(1L, userId, nickname, "USER");
    }
}
