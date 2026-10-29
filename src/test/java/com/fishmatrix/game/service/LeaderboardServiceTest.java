package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fishmatrix.common.auth.CurrentUser;
import com.fishmatrix.game.entity.GamePlayerGameStatEntity;
import com.fishmatrix.game.entity.GamePlayerStatEntity;
import com.fishmatrix.game.mapper.PlayerStatMapper;
import com.fishmatrix.game.model.LeaderboardEntry;
import com.fishmatrix.game.model.ScoreRequest;
import com.fishmatrix.game.model.ScoreResponse;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LeaderboardServiceTest {

    private PlayerStatMapper stats;
    private LeaderboardService service;

    @BeforeEach
    void setUp() {
        stats = mock(PlayerStatMapper.class);
        service = new LeaderboardService(stats);
    }

    @Test
    void returnsOverallLeaderboard() {
        when(stats.selectTop(10)).thenReturn(List.of(stat("u2", "乙", 2, 1, 70)));

        List<LeaderboardEntry> entries = service.leaderboard("qps-breakout", 10);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).userId()).isEqualTo("u2");
        assertThat(entries.get(0).championships()).isEqualTo(2);
        assertThat(entries.get(0).runnerUps()).isEqualTo(1);
        assertThat(entries.get(0).points()).isEqualTo(70);
    }

    @Test
    void returnsPerGameLeaderboardForBoardGames() {
        when(stats.selectTopByGame("blind-xiangqi", 10)).thenReturn(List.of(
                gameStat("u3", "丙", 30, 2, 2, 0, 0)));

        List<LeaderboardEntry> entries = service.leaderboard("blind-xiangqi", 10);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).userId()).isEqualTo("u3");
        verify(stats).selectTopByGame("blind-xiangqi", 10);
    }

    @Test
    void submitsBreakoutScoreWithServerIdentity() {
        when(stats.selectBestScore("u7")).thenReturn(300L);
        when(stats.selectRank("u7")).thenReturn(2L);

        ScoreResponse response = service.submit(
                new ScoreRequest("qps-breakout", "forged", "伪造昵称", 300), user(7, "甲"));

        assertThat(response.bestScore()).isEqualTo(300);
        assertThat(response.rank()).isEqualTo(2);
        verify(stats).raiseBestScore("u7", "甲", 300);
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

    private static GamePlayerStatEntity stat(String userId, String nickname,
                                             int championships, int runnerUps, int points) {
        GamePlayerStatEntity entity = new GamePlayerStatEntity();
        entity.setUserId(userId);
        entity.setNickname(nickname);
        entity.setChampionships(championships);
        entity.setRunnerUps(runnerUps);
        entity.setPoints(points);
        return entity;
    }

    private static GamePlayerGameStatEntity gameStat(String userId, String nickname, int points,
                                                     int matches, int wins, int losses,
                                                     int championships) {
        GamePlayerGameStatEntity entity = new GamePlayerGameStatEntity();
        entity.setUserId(userId);
        entity.setNickname(nickname);
        entity.setPoints(points);
        entity.setMatches(matches);
        entity.setWins(wins);
        entity.setLosses(losses);
        entity.setChampionships(championships);
        return entity;
    }

    private static CurrentUser user(long id, String nickname) {
        return new CurrentUser(id, "user-" + id, nickname, "USER", List.of("USER"), Set.of());
    }
}
