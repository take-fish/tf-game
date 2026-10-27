package com.fishmatrix.game.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.fishmatrix.game.model.TournamentPlayerRecord;
import com.fishmatrix.game.model.TournamentRecord;
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
class TournamentRepositoryTest {

    @Autowired
    private TournamentRepository tournaments;

    @Test
    void softDeleteHidesTournamentFromDetailAndList() {
        String id = UUID.randomUUID().toString();
        tournaments.insert(tournament(id, "FINISHED", "u1"));
        assertThat(tournaments.findById(id)).isPresent();
        assertThat(tournaments.listRecent(50))
                .extracting(TournamentRecord::id)
                .contains(id);

        long now = System.currentTimeMillis();
        assertThat(tournaments.softDelete(id, now)).isEqualTo(1);

        assertThat(tournaments.findById(id)).isEmpty();
        assertThat(tournaments.listRecent(50))
                .extracting(TournamentRecord::id)
                .doesNotContain(id);
        assertThat(tournaments.softDelete(id, now)).isZero();
    }

    @Test
    void softDeleteHidesPendingInvitation() {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        tournaments.insert(tournament(id, "INVITING", "u1"));
        tournaments.insertPlayer(new TournamentPlayerRecord(
                id, "u2", "受邀者", 1, "INVITED", now, null));
        assertThat(tournaments.pendingInvitations("u2", now))
                .extracting(TournamentRecord::id)
                .contains(id);

        assertThat(tournaments.softDelete(id, now)).isEqualTo(1);

        assertThat(tournaments.pendingInvitations("u2", now))
                .extracting(TournamentRecord::id)
                .doesNotContain(id);
    }

    @Test
    void runningTournamentIsNotSoftDeleted() {
        String id = UUID.randomUUID().toString();
        tournaments.insert(tournament(id, "RUNNING", "u1"));

        assertThat(tournaments.softDelete(id, System.currentTimeMillis())).isZero();
        assertThat(tournaments.findById(id)).isPresent();
    }

    @Test
    void duelsStayOutOfLobbyAndTournamentInvitations() {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        tournaments.insert(record(id, "DUEL", "INVITING", "u1"));
        tournaments.insertPlayer(new TournamentPlayerRecord(
                id, "u1", "发起者", 1, "ACCEPTED", now, now));
        tournaments.insertPlayer(new TournamentPlayerRecord(
                id, "u2", "对手", 2, "INVITED", now, null));

        assertThat(tournaments.listRecent(50))
                .extracting(TournamentRecord::id)
                .doesNotContain(id);
        assertThat(tournaments.pendingInvitations("u2", now)).isEmpty();
        assertThat(tournaments.listDuelsForUser("u1", 20))
                .extracting(TournamentRecord::id)
                .contains(id);
        assertThat(tournaments.listDuelsForUser("u2", 20))
                .extracting(TournamentRecord::id)
                .contains(id);
        assertThat(tournaments.listDuelsForUser("u3", 20))
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
        tournaments.insert(record(id, "DUEL", "FINISHED", "u4"));
        tournaments.insertPlayer(new TournamentPlayerRecord(
                id, "u5", "对手", 2, "ACCEPTED", now, now));

        assertThat(tournaments.existsActiveDuelBetween(
                "u4", "u5", "heatmap-gomoku")).isFalse();
        assertThat(tournaments.listDuelsForUser("u5", 20))
                .extracting(TournamentRecord::id)
                .contains(id);
    }

    @Test
    void activeDuelUniquenessIsPerGame() {
        long now = System.currentTimeMillis();
        String blindId = UUID.randomUUID().toString();
        String gomokuId = UUID.randomUUID().toString();
        tournaments.insert(record(blindId, "DUEL", "INVITING", "u6", "blind-xiangqi"));
        tournaments.insertPlayer(new TournamentPlayerRecord(
                blindId, "u6", "发起者", 1, "ACCEPTED", now, now));
        tournaments.insertPlayer(new TournamentPlayerRecord(
                blindId, "u7", "对手", 2, "INVITED", now, null));
        tournaments.insert(record(gomokuId, "DUEL", "INVITING", "u6", "heatmap-gomoku"));
        tournaments.insertPlayer(new TournamentPlayerRecord(
                gomokuId, "u6", "发起者", 1, "ACCEPTED", now, now));
        tournaments.insertPlayer(new TournamentPlayerRecord(
                gomokuId, "u7", "对手", 2, "INVITED", now, null));

        assertThat(tournaments.existsActiveDuelBetween(
                "u6", "u7", "blind-xiangqi")).isTrue();
        assertThat(tournaments.existsActiveDuelBetween(
                "u6", "u7", "heatmap-gomoku")).isTrue();
        assertThat(tournaments.existsActiveDuelBetween(
                "u6", "u7", "qps-breakout")).isFalse();
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
