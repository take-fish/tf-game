package com.fishmatrix.game.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fishmatrix.game.converter.SqlTime;
import com.fishmatrix.game.converter.TournamentConverters;
import com.fishmatrix.game.model.TournamentPlayerRecord;
import com.fishmatrix.game.model.TournamentRecord;
import java.util.List;
import java.util.Optional;
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
class TournamentMapperTest {

    @Autowired
    private TournamentMapper tournaments;

    @Test
    void softDeleteHidesTournamentFromDetailAndList() {
        String id = UUID.randomUUID().toString();
        tournaments.insert(TournamentConverters.toEntity(tournament(id, "FINISHED", "u1")));
        assertThat(find(id)).isPresent();
        assertThat(recent(50))
                .extracting(TournamentRecord::id)
                .contains(id);

        long now = System.currentTimeMillis();
        assertThat(tournaments.softDelete(id, SqlTime.toDateTime(now))).isEqualTo(1);

        assertThat(find(id)).isEmpty();
        assertThat(recent(50))
                .extracting(TournamentRecord::id)
                .doesNotContain(id);
        assertThat(tournaments.softDelete(id, SqlTime.toDateTime(now))).isZero();
    }

    @Test
    void softDeleteHidesPendingInvitation() {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        tournaments.insert(TournamentConverters.toEntity(tournament(id, "INVITING", "u1")));
        tournaments.insertPlayer(TournamentConverters.toEntity(new TournamentPlayerRecord(
                id, "u2", "受邀者", 1, "INVITED", now, null)));
        assertThat(pendingInvitations("u2", now))
                .extracting(TournamentRecord::id)
                .contains(id);

        assertThat(tournaments.softDelete(id, SqlTime.toDateTime(now))).isEqualTo(1);

        assertThat(pendingInvitations("u2", now))
                .extracting(TournamentRecord::id)
                .doesNotContain(id);
    }

    @Test
    void runningTournamentIsNotSoftDeleted() {
        String id = UUID.randomUUID().toString();
        tournaments.insert(TournamentConverters.toEntity(tournament(id, "RUNNING", "u1")));

        assertThat(tournaments.softDelete(id,
                SqlTime.toDateTime(System.currentTimeMillis()))).isZero();
        assertThat(find(id)).isPresent();
    }

    @Test
    void duelsStayOutOfLobbyAndTournamentInvitations() {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        tournaments.insert(TournamentConverters.toEntity(
                record(id, "DUEL", "INVITING", "u1")));
        tournaments.insertPlayer(TournamentConverters.toEntity(new TournamentPlayerRecord(
                id, "u1", "发起者", 1, "ACCEPTED", now, now)));
        tournaments.insertPlayer(TournamentConverters.toEntity(new TournamentPlayerRecord(
                id, "u2", "对手", 2, "INVITED", now, null)));

        assertThat(recent(50))
                .extracting(TournamentRecord::id)
                .doesNotContain(id);
        assertThat(pendingInvitations("u2", now)).isEmpty();
        assertThat(duels("u1", 20))
                .extracting(TournamentRecord::id)
                .contains(id);
        assertThat(duels("u2", 20))
                .extracting(TournamentRecord::id)
                .contains(id);
        assertThat(duels("u3", 20))
                .extracting(TournamentRecord::id)
                .doesNotContain(id);
        assertThat(tournaments.existsActiveDuelBetween(
                "u1", "u2", "heatmap-gomoku")).isTrue();
        assertThat(tournaments.existsActiveDuelBetween(
                "u2", "u1", "heatmap-gomoku")).isTrue();
        assertThat(tournaments.existsActiveDuelBetween(
                "u1", "u3", "heatmap-gomoku")).isFalse();
    }

    @Test
    void finishedDuelIsNoLongerActive() {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        tournaments.insert(TournamentConverters.toEntity(
                record(id, "DUEL", "FINISHED", "u4")));
        tournaments.insertPlayer(TournamentConverters.toEntity(new TournamentPlayerRecord(
                id, "u5", "对手", 2, "ACCEPTED", now, now)));

        assertThat(tournaments.existsActiveDuelBetween(
                "u4", "u5", "heatmap-gomoku")).isFalse();
        assertThat(duels("u5", 20))
                .extracting(TournamentRecord::id)
                .contains(id);
    }

    @Test
    void activeDuelUniquenessIsPerGame() {
        long now = System.currentTimeMillis();
        String blindId = UUID.randomUUID().toString();
        String gomokuId = UUID.randomUUID().toString();
        tournaments.insert(TournamentConverters.toEntity(
                record(blindId, "DUEL", "INVITING", "u6", "blind-xiangqi")));
        tournaments.insertPlayer(TournamentConverters.toEntity(new TournamentPlayerRecord(
                blindId, "u6", "发起者", 1, "ACCEPTED", now, now)));
        tournaments.insertPlayer(TournamentConverters.toEntity(new TournamentPlayerRecord(
                blindId, "u7", "对手", 2, "INVITED", now, null)));
        tournaments.insert(TournamentConverters.toEntity(
                record(gomokuId, "DUEL", "INVITING", "u6", "heatmap-gomoku")));
        tournaments.insertPlayer(TournamentConverters.toEntity(new TournamentPlayerRecord(
                gomokuId, "u6", "发起者", 1, "ACCEPTED", now, now)));
        tournaments.insertPlayer(TournamentConverters.toEntity(new TournamentPlayerRecord(
                gomokuId, "u7", "对手", 2, "INVITED", now, null)));

        assertThat(tournaments.existsActiveDuelBetween(
                "u6", "u7", "blind-xiangqi")).isTrue();
        assertThat(tournaments.existsActiveDuelBetween(
                "u6", "u7", "heatmap-gomoku")).isTrue();
        assertThat(tournaments.existsActiveDuelBetween(
                "u6", "u7", "qps-breakout")).isFalse();
    }

    @Test
    void seriesGamePoolRoundTripsThroughMapper() {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        tournaments.insert(TournamentConverters.toEntity(new TournamentRecord(
                id, "S赛", "random", "TOURNAMENT", "BO1", "TBD", "INVITING",
                "u8", "创建者", 2, now + 300_000L, 0, 0, null, null, null, null,
                now, null, null, "STANDARD_SHUFFLE",
                List.of("log-hotspot-arena", "blind-xiangqi"))));

        TournamentRecord stored = find(id).orElseThrow();
        assertThat(stored.gamePool())
                .containsExactly("log-hotspot-arena", "blind-xiangqi");
        assertThat(stored.blindLayout()).isEqualTo("STANDARD_SHUFFLE");
    }

    private Optional<TournamentRecord> find(String id) {
        return Optional.ofNullable(tournaments.selectActiveById(id))
                .map(TournamentConverters::toRecord);
    }

    private List<TournamentRecord> recent(int limit) {
        return TournamentConverters.records(tournaments.selectRecent(limit));
    }

    private List<TournamentRecord> duels(String userId, int limit) {
        return TournamentConverters.records(tournaments.selectDuelsForUser(userId, limit));
    }

    private List<TournamentRecord> pendingInvitations(String userId, long now) {
        return TournamentConverters.records(
                tournaments.selectPendingInvitations(userId, SqlTime.toDateTime(now)));
    }

    private TournamentRecord tournament(String id, String status, String creator) {
        return record(id, "TOURNAMENT", status, creator);
    }

    private TournamentRecord record(String id, String kind, String status, String creator) {
        return record(id, kind, status, creator, "heatmap-gomoku");
    }

    private TournamentRecord record(String id, String kind, String status,
                                    String creator, String gameId) {
        long now = System.currentTimeMillis();
        return new TournamentRecord(
                id, "演练", gameId, kind, "BO1", "TBD", status,
                creator, "创建者", 2, now + 300_000L, 0, 0, null, null,
                now, null, null);
    }
}
