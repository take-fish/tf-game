package com.fishmatrix.game.controller;

import com.fishmatrix.common.auth.CurrentUser;
import com.fishmatrix.common.auth.UserContext;
import com.fishmatrix.common.autoconfigure.FishCommonProperties;
import com.fishmatrix.common.error.GlobalExceptionHandler;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fishmatrix.game.model.LeaderboardEntry;
import com.fishmatrix.game.model.ScoreRequest;
import com.fishmatrix.game.model.ScoreResponse;
import com.fishmatrix.game.service.LeaderboardService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GameControllerTest {

    private LeaderboardService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(LeaderboardService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new GameController(service))
                .setControllerAdvice(new GlobalExceptionHandler(new FishCommonProperties()))
                .build();
        UserContext.set(new CurrentUser(7, "jia", "甲", "USER", List.of("USER"), Set.of()));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void returnsLeaderboard() throws Exception {
        when(service.leaderboard("qps-breakout", 10))
                .thenReturn(List.of(new LeaderboardEntry(1, "user-a", "甲", 3, 2, 110)));

        mockMvc.perform(get("/api/v1/game/leaderboard").param("gameId", "qps-breakout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].nickname").value("甲"))
                .andExpect(jsonPath("$.entries[0].championships").value(3))
                .andExpect(jsonPath("$.entries[0].runnerUps").value(2))
                .andExpect(jsonPath("$.entries[0].points").value(110));
    }

    @Test
    void submitsScore() throws Exception {
        when(service.submit(any(ScoreRequest.class), any(CurrentUser.class)))
                .thenReturn(new ScoreResponse(120, 2));

        mockMvc.perform(post("/api/v1/game/scores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameId\":\"qps-breakout\",\"userId\":\"user-a\","
                                + "\"nickname\":\"甲\",\"score\":120}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestScore").value(120))
                .andExpect(jsonPath("$.rank").value(2));
    }

    @Test
    void mapsBadRequest() throws Exception {
        when(service.leaderboard("bad", 10))
                .thenThrow(new IllegalArgumentException("游戏不存在"));

        mockMvc.perform(get("/api/v1/game/leaderboard").param("gameId", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }
}
