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

    private TournamentRecord tournament(String id, String status, String creator) {
        long now = System.currentTimeMillis();
        return new TournamentRecord(
                id, "演练", "heatmap-gomoku", "BO1", "TBD", status,
                creator, "创建者", 2, now + 300_000L, 0, 0, null, null,
                now, null, null);
    }
}
