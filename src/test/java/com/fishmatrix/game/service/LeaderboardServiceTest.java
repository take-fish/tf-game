package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fishmatrix.game.model.LeaderboardEntry;
import com.fishmatrix.game.model.ScoreRequest;
import com.fishmatrix.game.model.ScoreResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LeaderboardServiceTest {

    private FakeLeaderboardStore store;
    private LeaderboardService service;

    @BeforeEach
    void setUp() {
        store = new FakeLeaderboardStore();
        service = new LeaderboardService(store);
    }

    @Test
    void keepsBestScoreAndComputesRank() {
        ScoreResponse first = service.submit(new ScoreRequest("qps-breakout", "user-a", "甲", 120));
        ScoreResponse lower = service.submit(new ScoreRequest("qps-breakout", "user-a", "甲", 80));
        ScoreResponse higher = service.submit(new ScoreRequest("qps-breakout", "user-b", "乙", 300));

        assertThat(first.bestScore()).isEqualTo(120);
        assertThat(lower.bestScore()).isEqualTo(120);
        assertThat(higher.rank()).isEqualTo(1);

        List<LeaderboardEntry> entries = service.leaderboard("qps-breakout", 10);
        assertThat(entries).extracting(LeaderboardEntry::userId)
                .containsExactly("user-b", "user-a");
        assertThat(entries.get(1).score()).isEqualTo(120);
    }

    @Test
    void rejectsInvalidGameAndScore() {
        assertThatThrownBy(() -> service.leaderboard("unknown", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("游戏");
        assertThatThrownBy(() -> service.submit(
                        new ScoreRequest("qps-breakout", "user-a", "甲", 1_000_001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分数");
        assertThatThrownBy(() -> service.submit(
                        new ScoreRequest("qps-breakout", "bad id!", "甲", 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }
}
