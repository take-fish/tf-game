package com.fishmatrix.game.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.fishmatrix.game.store.ExerciseRepository.MemberRow;
import com.fishmatrix.game.store.ExerciseRepository.ResultRow;
import com.fishmatrix.game.store.ExerciseRepository.RoomRow;
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
class ExerciseRepositoryTest {

    @Autowired
    private ExerciseRepository repository;

    @Test
    void runningRoomIsSnapshottedAndRecovered() {
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        repository.insertRoom(new RoomRow(
                id, "AB12CD", "FFA", "CASUAL", "WAITING", null, "u1",
                4, 180, null, 0, null, null, null, now, null, null));
        repository.insertMember(new MemberRow(
                id, "u1", "甲", null, true, false, 0, 0, 0, 0, now, null));

        repository.updateRoomState(id, "{\"seq\":1}", 1, "RUNNING");

        assertThat(repository.listRunningRooms())
                .extracting(RoomRow::id).contains(id);
        assertThat(repository.listMembers(id)).hasSize(1);
    }

    @Test
    void finishedRoomWritesResultsAndLeavesRunningList() {
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        repository.insertRoom(new RoomRow(
                id, "EF34GH", "TEAM", "CASUAL", "RUNNING", null, "u1",
                2, 180, "{\"seq\":2}", 2, null, null, null, now, now, null));
        repository.updateMemberStats(id, "u1", 9, 2, 0, 1);
        repository.finishRoom(
                id, "u1", "A", "团队积分 9 : 5", "{\"seq\":3}", 3, now + 1_000);
        repository.insertResult(new ResultRow(
                UUID.randomUUID().toString(), id, "TEAM", "u1", "甲", "A",
                1, 9, 2, 0, 1, true, now + 1_000));

        assertThat(repository.listRunningRooms())
                .extracting(RoomRow::id).doesNotContain(id);
        assertThat(repository.listRecentResults(20))
                .anySatisfy(result -> {
                    assertThat(result.roomId()).isEqualTo(id);
                    assertThat(result.userId()).isEqualTo("u1");
                    assertThat(result.winner()).isTrue();
                    assertThat(result.score()).isEqualTo(9);
                });
    }

    @Test
    void trainingResultsStayOutOfRecentList() {
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        repository.insertRoom(new RoomRow(
                id, "IJ56KL", "TRAINING", "CASUAL", "FINISHED", null, "u1",
                1, 180, null, 0, "u1", null, "训练结束", now, now, now));
        repository.insertResult(new ResultRow(
                UUID.randomUUID().toString(), id, "TRAINING", "u1", "甲", null,
                1, 12, 3, 0, 0, true, now));

        assertThat(repository.listRecentResults(100))
                .noneMatch(result -> id.equals(result.roomId()));
    }
}
