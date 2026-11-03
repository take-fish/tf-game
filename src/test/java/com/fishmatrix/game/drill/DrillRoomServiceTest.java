package com.fishmatrix.game.drill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DrillRoomServiceTest {

    private final DrillRoomService service = new DrillRoomService();

    private static GameIdentity player(long id) {
        return new GameIdentity(id, "u" + id, "成员" + id, "PLAYER");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void 两人就绪后开局并同步轮次() {
        Map<String, Object> created = service.create(player(1), "CASUAL");
        String roomId = (String) created.get("id");
        assertEquals("WAITING", created.get("status"));
        assertTrue(created.get("seed") instanceof Long);

        service.join(player(2), roomId);
        service.ready(player(1), roomId);
        Map<String, Object> running = service.ready(player(2), roomId);
        assertEquals("RUNNING", running.get("status"));
        assertEquals(1, running.get("round"));
        assertEquals(2, ((List<?>) running.get("players")).size());
    }

    @Test
    void 血量归零即淘汰并按存活排定名次() {
        String roomId = (String) service.create(player(1), "CASUAL").get("id");
        service.join(player(2), roomId);
        service.ready(player(1), roomId);
        service.ready(player(2), roomId);

        service.report(player(1), roomId, 1, 80, null);
        Map<String, Object> finished = service.report(player(2), roomId, 1, 0, null);

        assertEquals("FINISHED", finished.get("status"));
        assertEquals(List.of("u1", "u2"), finished.get("placement"));
        Map<String, Object> first = asMap(((List<?>) finished.get("players")).get(0));
        assertEquals("u1", first.get("userId"));
        assertTrue((Boolean) first.get("alive"));
    }

    @Test
    void 选秀候选先到先得() {
        String roomId = (String) service.create(player(1), "CASUAL").get("id");
        service.join(player(2), roomId);
        service.ready(player(1), roomId);
        service.ready(player(2), roomId);

        service.report(player(1), roomId, 1, 90, "@Async");
        Map<String, Object> view = service.report(player(2), roomId, 1, 90, "@Async");
        assertEquals(Map.of("@Async", "u1"), view.get("carouselWins"));
        assertEquals(2, view.get("round"));
    }

    @Test
    void 轮次不一致时拒绝报到() {
        String roomId = (String) service.create(player(1), "CASUAL").get("id");
        service.join(player(2), roomId);
        service.ready(player(1), roomId);
        service.ready(player(2), roomId);
        assertThrows(GameException.class, () -> service.report(player(1), roomId, 3, 50, null));
    }
}
