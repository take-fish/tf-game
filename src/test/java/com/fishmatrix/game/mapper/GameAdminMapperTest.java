package com.fishmatrix.game.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fishmatrix.game.converter.SqlTime;
import com.fishmatrix.game.converter.TournamentConverters;
import com.fishmatrix.game.domain.MatchStage;
import com.fishmatrix.game.domain.MatchStatus;
import com.fishmatrix.game.entity.GameMatchEntity;
import com.fishmatrix.game.model.MatchRecordPreview;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.service.GameRecordService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 管理字段的持久化与排序语义：公开查询过滤隐藏行，管理查询包含隐藏行，
 * 展示权重优先于战绩/时间，比赛记录列表只取赛事类记录。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.cloud.nacos.discovery.enabled=false",
            "spring.cloud.nacos.config.enabled=false",
            "app.game.scheduling-enabled=false"
        })
class GameAdminMapperTest {

    @Autowired
    private PlayerStatMapper stats;

    @Autowired
    private MatchMapper matches;

    @Autowired
    private TournamentMapper tournaments;

    @Autowired
    private GameRecordService records;

    @Test
    void displayOrderWinsOverPointsAndHiddenRowsLeavePublicBoard() {
        String low = userId();
        String high = userId();
        stats.ensure(low, "低分");
        stats.ensure(high, "高分");
        LocalDateTime now = LocalDateTime.now();
        stats.addTitleStats(low, "低分", 1, 0, 10, now);
        stats.addTitleStats(high, "高分", 0, 0, 20, now);

        List<String> byPoints = publicBoard()
                .stream()
                .map(entry -> entry.userId())
                .toList();
        assertThat(byPoints.indexOf(high)).isLessThan(byPoints.indexOf(low));

        // 把低分选手顶到最前
        assertThat(stats.updateManaged(low, null, null, null, null, null, null, null, null, 5,
                "admin", now)).isEqualTo(1);
        assertThat(publicBoard().get(0).userId()).isEqualTo(low);
        assertThat(stats.selectRank(low)).isEqualTo(1L);

        // 下架高分选手后公共榜不再出现，但管理台仍能看到
        assertThat(stats.updateManaged(high, null, null, null, null, null, null, null, false, null,
                "admin", now)).isEqualTo(1);
        assertThat(publicBoard()).extracting(entry -> entry.userId()).doesNotContain(high);
        assertThat(stats.selectAllOverall(200)).extracting(row -> row.getUserId()).contains(high);

        var managed = stats.selectAllOverall(200).stream()
                .filter(row -> row.getUserId().equals(high))
                .findFirst()
                .orElseThrow();
        assertThat(managed.getVisible()).isFalse();
        assertThat(managed.getManagedBy()).isEqualTo("admin");
        assertThat(managed.getManagedAt()).isNotNull();
    }

    @Test
    void perGameBoardHonoursDisplayOrderAndVisibility() {
        String gameId = "heatmap-gomoku";
        String first = userId();
        String second = userId();
        stats.addGameMatch(gameId, first, "甲", 1, 0, 5, 0);
        stats.addGameMatch(gameId, second, "乙", 1, 0, 9, 0);
        LocalDateTime now = LocalDateTime.now();

        assertThat(stats.updateManagedGame(gameId, first, null, null, null, null, null, null, 7,
                "admin", now)).isEqualTo(1);
        List<String> board = stats.selectTopByGame(gameId, 200).stream()
                .map(row -> row.getUserId())
                .toList();
        assertThat(board.indexOf(first)).isLessThan(board.indexOf(second));

        assertThat(stats.updateManagedGame(gameId, second, null, null, null, null, null, false, null,
                "admin", now)).isEqualTo(1);
        assertThat(stats.selectTopByGame(gameId, 200))
                .extracting(row -> row.getUserId())
                .doesNotContain(second);
        assertThat(stats.selectGameStat(gameId, second).getVisible()).isFalse();
        assertThat(stats.selectAllByGame(gameId, 200))
                .extracting(row -> row.getUserId())
                .contains(second);
    }

    @Test
    void recordListFiltersHiddenRowsAndSkipsDuelsAndDeletedTournaments() {
        String tournamentId = tournament("TOURNAMENT", "INVITING");
        String duelId = tournament("DUEL", "INVITING");
        String hiddenMatch = match(tournamentId, 1, 1, 1_000L);
        String visibleMatch = match(tournamentId, 1, 2, 2_000L);
        String duelMatch = match(duelId, 1, 1, 3_000L);
        LocalDateTime now = LocalDateTime.now();

        List<String> publicIds = ids(records.list(50));
        assertThat(publicIds).contains(hiddenMatch, visibleMatch).doesNotContain(duelMatch);

        assertThat(matches.updateRecordManaged(hiddenMatch, Boolean.FALSE, 9, "admin", now))
                .isEqualTo(1);
        assertThat(matches.updateRecordManaged(visibleMatch, null, 1, "admin", now)).isEqualTo(1);

        // 权重高的仍然隐藏，公开列表只剩可见的那条
        assertThat(ids(records.list(50))).contains(visibleMatch).doesNotContain(hiddenMatch);
        // 管理台列表包含隐藏行，且按展示权重排序
        List<MatchRecordPreview> adminList = records.listForAdmin(50);
        assertThat(ids(adminList)).contains(hiddenMatch).doesNotContain(duelMatch);
        assertThat(adminList.get(0).matchId()).isEqualTo(hiddenMatch);
        assertThat(adminList.get(0).visible()).isFalse();
        assertThat(adminList.get(0).displayOrder()).isEqualTo(9);
        assertThat(adminList.get(0).tournamentName()).isEqualTo("治理演练");
        assertThat(adminList.get(0).gameId()).isEqualTo("heatmap-gomoku");

        // 软删除赛事后记录整体消失
        assertThat(tournaments.softDelete(tournamentId, now)).isEqualTo(1);
        assertThat(ids(records.listForAdmin(50))).doesNotContain(hiddenMatch, visibleMatch);
    }

    private List<com.fishmatrix.game.model.LeaderboardEntry> publicBoard() {
        return com.fishmatrix.game.converter.PlayerStatConverters.entries(stats.selectTop(200));
    }

    private static List<String> ids(List<MatchRecordPreview> previews) {
        return previews.stream().map(MatchRecordPreview::matchId).toList();
    }

    private String tournament(String kind, String status) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        tournaments.insert(TournamentConverters.toEntity(new TournamentRecord(
                id, "治理演练", "heatmap-gomoku", kind, "BO1", "TBD", status,
                "u" + id.substring(0, 8), "创建者", 2, now + 300_000L, 0, 0, null, null,
                now, null, null)));
        return id;
    }

    private String match(String tournamentId, int roundNo, int slotNo, long createdAt) {
        GameMatchEntity entity = new GameMatchEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setTournamentId(tournamentId);
        entity.setRoundNo(roundNo);
        entity.setSlotNo(slotNo);
        entity.setStage(MatchStage.KNOCKOUT);
        entity.setStatus(MatchStatus.FINISHED);
        entity.setPlayer1UserId("u1");
        entity.setPlayer1Nickname("甲");
        entity.setPlayer2UserId("u2");
        entity.setPlayer2Nickname("乙");
        entity.setPlayer1Score(2);
        entity.setPlayer2Score(1);
        entity.setWinnerUserId("u1");
        entity.setCreatedAt(SqlTime.toDateTime(createdAt));
        matches.insert(entity);
        return entity.getId();
    }

    private static String userId() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
