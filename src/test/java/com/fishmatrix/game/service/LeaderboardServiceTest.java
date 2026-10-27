package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fishmatrix.common.auth.CurrentUser;
import com.fishmatrix.game.model.LeaderboardEntry;
import com.fishmatrix.game.model.ScoreRequest;
import com.fishmatrix.game.model.ScoreResponse;
import com.fishmatrix.game.store.PlayerStatRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LeaderboardServiceTest {

    private PlayerStatRepository stats;
    private LeaderboardService service;

    @BeforeEach
    void setUp() {
        stats = mock(PlayerStatRepository.class);
        service = new LeaderboardService(stats);
    }

    @Test
    void returnsOverallLeaderboard() {
        when(stats.top(10)).thenReturn(List.of(
                new LeaderboardEntry(1, "u2", "乙", 2, 1, 70)));

        List<LeaderboardEntry> entries = service.leaderboard("qps-breakout", 10);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).userId()).isEqualTo("u2");
        assertThat(entries.get(0).championships()).isEqualTo(2);
        assertThat(entries.get(0).runnerUps()).isEqualTo(1);
        assertThat(entries.get(0).points()).isEqualTo(70);
    }

    @Test
    void returnsPerGameLeaderboardForBoardGames() {
        when(stats.topByGame("blind-xiangqi", 10)).thenReturn(List.of(
                new LeaderboardEntry(1, "u3", "丙", 30, 30, 2, 2, 0, 0, 0)));

        List<LeaderboardEntry> entries = service.leaderboard("blind-xiangqi", 10);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).userId()).isEqualTo("u3");
        verify(stats).topByGame("blind-xiangqi", 10);
    }

    @Test
    void submitsBreakoutScoreWithServerIdentity() {
        when(stats.bestScore("u7")).thenReturn(300L);
        when(stats.rank("u7")).thenReturn(2L);

        ScoreResponse response = service.submit(
                new ScoreRequest("qps-breakout", "forged", "伪造昵称", 300), user(7, "甲"));

        assertThat(response.bestScore()).isEqualTo(300);
        assertThat(response.rank()).isEqualTo(2);
        verify(stats).recordBreakoutScore("u7", "甲", 300);
    }

    @Test
    void rejectsInvalidGameScoreAndNickname() {
        assertThatThrownBy(() -> service.submit(
                new ScoreRequest("unknown", "u1", "甲", 10), user(1, "甲")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("游戏");
        assertThatThrownBy(() -> service.submit(
                new ScoreRequest("qps-breakout", "u1", "甲", 1_000_001), user(1, "甲")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分数");
        assertThatThrownBy(() -> service.submit(
                new ScoreRequest("qps-breakout", "u1", "甲", 10), user(1, " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("昵称");
    }

    private static CurrentUser user(long id, String nickname) {
        return new CurrentUser(id, "user-" + id, nickname, "USER", List.of("USER"), Set.of());
    }
}
