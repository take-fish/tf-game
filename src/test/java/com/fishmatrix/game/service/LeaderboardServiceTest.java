package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fishmatrix.common.auth.CurrentUser;
import com.fishmatrix.game.model.LeaderboardEntry;
import com.fishmatrix.game.model.ScoreRequest;
import com.fishmatrix.game.model.ScoreResponse;
import java.util.List;
import java.util.Set;
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
        ScoreResponse first = service.submit(new ScoreRequest("qps-breakout", "ignored", "ignored", 120), user(1, "甲"));
        ScoreResponse lower = service.submit(new ScoreRequest("qps-breakout", "ignored", "ignored", 80), user(1, "甲"));
        ScoreResponse higher = service.submit(new ScoreRequest("qps-breakout", "ignored", "ignored", 300), user(2, "乙"));

        assertThat(first.bestScore()).isEqualTo(120);
        assertThat(lower.bestScore()).isEqualTo(120);
        assertThat(higher.rank()).isEqualTo(1);

        List<LeaderboardEntry> entries = service.leaderboard("qps-breakout", 10);
        assertThat(entries).extracting(LeaderboardEntry::userId)
                .containsExactly("u2", "u1");
        assertThat(entries.get(1).score()).isEqualTo(120);
    }

    private static CurrentUser user(long id, String nickname) {
        return new CurrentUser(id, "user-" + id, nickname, "USER", List.of("USER"), Set.of());
    }

    @Test
    void rejectsInvalidGameAndScore() {
        assertThatThrownBy(() -> service.leaderboard("unknown", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("游戏");
        assertThatThrownBy(() -> service.submit(
                        new ScoreRequest("qps-breakout", "ignored", "ignored", 1_000_001), user(1, "甲")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分数");
        assertThatThrownBy(() -> service.submit(
                        new ScoreRequest("qps-breakout", "ignored", "ignored", 10), user(1, " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("昵称");
    }
}
