package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.TournamentRecord;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 同一轮里的多场对阵应当分到不同剧本，避免整轮所有比赛都是同一款游戏。
 */
class TournamentRoundGameDiversityTest {

    private static final List<String> POOL = List.of(
            "heatmap-gomoku", "topology-arena", "cluster-patrol-arena", "blind-xiangqi");

    @Test
    void sameRoundMatchesRotateThroughPool() {
        TournamentRecord tournament = tournament("random", POOL);
        Set<String> games = new LinkedHashSet<>();
        for (int slot = 1; slot <= POOL.size(); slot += 1) {
            games.add(TournamentRules.resolveRoundGame(tournament, match(1, slot)));
        }
        assertThat(games).hasSize(POOL.size());
        assertThat(games).isSubsetOf(POOL);
    }

    @Test
    void nextRoundStartsFromAShiftedGame() {
        TournamentRecord tournament = tournament("random", POOL);
        String firstOfRoundOne = TournamentRules.resolveRoundGame(tournament, match(1, 1));
        String firstOfRoundTwo = TournamentRules.resolveRoundGame(tournament, match(2, 1));
        assertThat(firstOfRoundTwo).isNotEqualTo(firstOfRoundOne);
    }

    @Test
    void fixedGameTournamentKeepsSameGame() {
        TournamentRecord tournament = tournament("heatmap-gomoku", List.of());
        assertThat(TournamentRules.resolveRoundGame(tournament, match(3, 5)))
                .isEqualTo("heatmap-gomoku");
    }

    private static MatchRecord match(int round, int slot) {
        return new MatchRecord("m-" + round + "-" + slot, "t1", round, slot,
                BracketPlanner.STAGE_KNOCKOUT, BracketPlanner.STATUS_READY,
                "u1", "A", "u2", "B", 0, 0, null, null, System.currentTimeMillis(), null, null);
    }

    private static TournamentRecord tournament(String gameId, List<String> pool) {
        return new TournamentRecord("t1", "演练赛", gameId, "TOURNAMENT", "BO1",
                "SINGLE_ELIMINATION", "RUNNING", "u1", "A", 8, 0, 1, 3,
                null, null, null, null, System.currentTimeMillis(), null, null, null, pool);
    }
}
