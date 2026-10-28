package com.fishmatrix.game.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.fishmatrix.game.model.LeaderboardEntry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.cloud.nacos.discovery.enabled=false",
            "spring.cloud.nacos.config.enabled=false",
            "app.game.scheduling-enabled=false"
        })
class PlayerStatRepositoryTest {

    @Autowired
    private PlayerStatRepository stats;

    @Test
    void recordsPerGameLeaderboardsSeparately() {
        String first = userId();
        String second = userId();

        stats.addGameMatch("blind-xiangqi", first, "盲棋甲", 1, 0, 3, 0);
        stats.addGameMatch("blind-xiangqi", second, "盲棋乙", 0, 1, 1, 0);
        stats.addGameMatch("heatmap-gomoku", first, "五子甲", 0, 1, 1, 0);

        LeaderboardEntry blindFirst = find(stats.topByGame("blind-xiangqi", 50), first);
        assertThat(blindFirst.points()).isEqualTo(3);
        assertThat(blindFirst.wins()).isEqualTo(1);
        assertThat(blindFirst.losses()).isZero();
        assertThat(blindFirst.matches()).isEqualTo(1);

        LeaderboardEntry blindSecond = find(stats.topByGame("blind-xiangqi", 50), second);
        assertThat(blindSecond.points()).isEqualTo(1);
        assertThat(blindSecond.wins()).isZero();
        assertThat(blindSecond.losses()).isEqualTo(1);

        LeaderboardEntry gomokuFirst = find(stats.topByGame("heatmap-gomoku", 50), first);
        assertThat(gomokuFirst.points()).isEqualTo(1);
        assertThat(gomokuFirst.wins()).isZero();
        assertThat(gomokuFirst.losses()).isEqualTo(1);
    }

    private static LeaderboardEntry find(List<LeaderboardEntry> entries, String userId) {
        return entries.stream()
                .filter(entry -> entry.userId().equals(userId))
                .findFirst()
                .orElseThrow();
    }

    private static String userId() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
