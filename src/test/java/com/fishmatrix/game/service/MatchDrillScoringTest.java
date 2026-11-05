package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.game.model.DrillReportRequest;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.RoundRecord;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MatchDrillScoringTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MatchDrillService.Report winLow = report(1, 0, 0, 40, 3);
    private final MatchDrillService.Report winHigh = report(1, 0, 0, 80, 3);
    private final MatchDrillService.Report draw = report(0, 0, 1, 99, 8);
    private final MatchDrillService.Report loss = report(0, 1, 0, 99, 8);

    @Test
    void comparesByScoreThenSliThenSurvivors() {
        assertThat(MatchDrillService.compare(winHigh, draw)).isPositive();
        assertThat(MatchDrillService.compare(draw, loss)).isPositive();
        assertThat(MatchDrillService.compare(winLow, winHigh)).isNegative();
        assertThat(MatchDrillService.compare(
                report(1, 0, 0, 60, 2), report(1, 0, 0, 60, 5))).isNegative();
        assertThat(MatchDrillService.compare(winHigh, winHigh)).isZero();
    }

    @Test
    void mapsWinnerAndFallsBackToNullOnPerfectTie() {
        MatchRecord match = match();
        assertThat(MatchDrillService.compareWinner(match, Map.of("u1", winHigh, "u2", draw)))
                .isEqualTo("u1");
        assertThat(MatchDrillService.compareWinner(match, Map.of("u1", loss, "u2", winLow)))
                .isEqualTo("u2");
        assertThat(MatchDrillService.compareWinner(match, Map.of("u1", winHigh, "u2", winHigh)))
                .isNull();
        assertThat(MatchDrillService.compareWinner(match, Map.of("u1", winHigh))).isNull();
    }

    @Test
    void validatesSingleBattleReport() {
        MatchDrillService.validateReport(new DrillReportRequest(1, 1, 0, 0, 55, 4));
        MatchDrillService.validateReport(new DrillReportRequest(1, 0, 1, 0, 55, 4));
        MatchDrillService.validateReport(new DrillReportRequest(1, 0, 0, 1, 55, 4));

        assertThatThrownBy(() ->
                MatchDrillService.validateReport(new DrillReportRequest(1, 1, 1, 0, 55, 4)))
                .isInstanceOf(GameException.class);
        assertThatThrownBy(() ->
                MatchDrillService.validateReport(new DrillReportRequest(1, 1, 0, 0, -1, 4)))
                .isInstanceOf(GameException.class);
        assertThatThrownBy(() ->
                MatchDrillService.validateReport(new DrillReportRequest(1, 1, 0, 0, 55, 9)))
                .isInstanceOf(GameException.class);
    }

    @Test
    void exposesSeedAndSubmittedIdsButNotOpponentReport() {
        String state = "{\"seed\":123,\"gameNo\":2,\"deadlineAt\":99,"
                + "\"reports\":{\"u1\":{\"wins\":1,\"losses\":0,\"draws\":0,\"sli\":50,\"survivors\":3}}}";
        Map<String, Object> view = MatchDrillService.view(round(state), MAPPER);

        assertThat(view).containsEntry("seed", 123);
        assertThat(view).containsEntry("gameNo", 2);
        assertThat(view).containsEntry("deadlineAt", 99L);
        assertThat(view.get("reportedUserIds")).isEqualTo(java.util.List.of("u1"));
        assertThat(view).doesNotContainKeys("reports", "wins");
    }

    @Test
    void ignoresNonDrillRounds() {
        assertThat(MatchDrillService.view(round("{}", "heatmap-gomoku"), MAPPER)).isNull();
    }

    private static MatchDrillService.Report report(
            int wins, int losses, int draws, int sli, int survivors) {
        return new MatchDrillService.Report(wins, losses, draws, sli, survivors);
    }

    private static MatchRecord match() {
        return new MatchRecord("m1", "t1", 1, 0, "KNOCKOUT", "RUNNING",
                "u1", "甲", "u2", "乙", 0, 0, null, null,
                0L, null, null, "capacity-drill", null);
    }

    private static RoundRecord round(String stateJson) {
        return round(stateJson, "capacity-drill");
    }

    private static RoundRecord round(String stateJson, String gameId) {
        return new RoundRecord("r1", "m1", gameId, 1, "RUNNING",
                "u1", "u2", null, null, null, 0, 0L, null, "",
                stateJson, 0, 0L, 99L, null);
    }
}
