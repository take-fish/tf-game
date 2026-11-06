package com.fishmatrix.game.drill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.TournamentPlayerRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** 赛事创建排位赛房间 → 15 轮 → 自动淘汰赛 → 冠军回写事件 的串联。 */
class DrillTournamentFlowTest {

    private final List<Object> events = new ArrayList<>();
    private final ApplicationEventPublisher publisher = events::add;
    private final DrillRoomService rooms = new DrillRoomService(publisher);
    private final DrillKnockoutService knockout = new DrillKnockoutService(rooms, publisher);

    private static GameIdentity player(long id) {
        return new GameIdentity(id, "u" + id, "成员" + id, "PLAYER");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static TournamentPlayerRecord seat(long id) {
        return new TournamentPlayerRecord(
                "t1", "u" + id, "成员" + id, (int) id, "ACCEPTED", 0L, 0L);
    }

    private String tournamentRoom() {
        Map<String, Object> room = rooms.createForTournament("t1", List.of(seat(1), seat(2)));
        String roomId = (String) room.get("id");
        rooms.ready(player(1), roomId);
        rooms.ready(player(2), roomId);
        for (int round = 1; round <= DrillRoomService.TOURNAMENT_ROUNDS; round += 1) {
            rooms.requestSkip(player(1), roomId);
            rooms.requestSkip(player(2), roomId);
            rooms.report(player(1), roomId, round, 100, "WIN", null);
            rooms.report(player(2), roomId, round, 100, "LOSS", null);
        }
        return roomId;
    }

    private static void playHumanMatch(
            DrillKnockoutService knockout, String bracketId, String matchId, GameIdentity human,
            int wins) {
        for (int game = 1; game <= wins; game += 1) {
            knockout.report(human, bracketId, matchId,
                    new DrillKnockoutService.GameReport(game, 1, 0, 0, 100, 8));
        }
    }

    @Test
    void 赛事房间只对参赛选手开放() {
        Map<String, Object> room = rooms.createForTournament("t1", List.of(seat(1), seat(2)));
        String roomId = (String) room.get("id");

        assertEquals("u1", ((Map<?, ?>) ((List<?>) room.get("players")).get(0)).get("userId"));
        assertEquals(roomId, asMap(rooms.tournamentRoom("t1", "u2")).get("id"));
        assertEquals("t1", rooms.tournamentIdOf(roomId));
        assertThrows(GameException.class, () -> rooms.tournamentRoom("t1", "u9"));
        assertThrows(GameException.class, () -> rooms.join(player(9), roomId));

        // 同一赛事重复创建返回同一房间
        assertEquals(roomId, rooms.createForTournament("t1", List.of(seat(1), seat(2))).get("id"));
    }

    @Test
    void 排位赛结束发出事件并可自动开淘汰赛() {
        String roomId = tournamentRoom();

        assertTrue(events.stream().anyMatch(event ->
                event instanceof DrillTournamentEvents.PlacementFinished finished
                        && "t1".equals(finished.tournamentId())
                        && roomId.equals(finished.roomId())));

        Map<String, Object> bracket = knockout.startInternal(roomId);
        assertEquals("t1", bracket.get("tournamentId"));
        // 幂等：重复调用不会另开一届
        assertEquals(bracket.get("id"), knockout.startInternal(roomId).get("id"));
    }

    @Test
    void 决赛开始与冠军产生都发出事件() {
        String roomId = tournamentRoom();
        String bracketId = (String) knockout.startInternal(roomId).get("id");
        List<String> ids = knockout.matchIds(bracketId);

        // 八强：u1 vs bot-6，u2 vs bot-5；其余两场机器人内战直接晋级
        playHumanMatch(knockout, bracketId, ids.get(0), player(1), 2);
        playHumanMatch(knockout, bracketId, ids.get(3), player(2), 2);

        ids = knockout.matchIds(bracketId);
        playHumanMatch(knockout, bracketId, ids.get(4), player(1), 3);
        playHumanMatch(knockout, bracketId, ids.get(5), player(2), 3);

        assertTrue(events.stream().anyMatch(event ->
                event instanceof DrillTournamentEvents.FinalStart finalStart
                        && "t1".equals(finalStart.tournamentId())
                        && "成员1".equals(finalStart.playerA())
                        && "成员2".equals(finalStart.playerB())));

        ids = knockout.matchIds(bracketId);
        assertEquals(7, ids.size());
        for (int game = 1; game <= 3; game += 1) {
            knockout.report(player(1), bracketId, ids.get(6),
                    new DrillKnockoutService.GameReport(game, 1, 0, 0, 100, 8));
            knockout.report(player(2), bracketId, ids.get(6),
                    new DrillKnockoutService.GameReport(game, 0, 1, 0, 40, 2));
        }

        assertTrue(events.stream().anyMatch(event ->
                event instanceof DrillTournamentEvents.BracketFinished finished
                        && "t1".equals(finished.tournamentId())
                        && "u1".equals(finished.championId())
                        && "成员1".equals(finished.championNickname())
                        && "u2".equals(finished.runnerUpId())
                        && "成员2".equals(finished.runnerUpNickname())));
    }
}
