package com.fishmatrix.game.drill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DrillKnockoutServiceTest {

    private final DrillRoomService rooms = new DrillRoomService();
    private final DrillKnockoutService knockout = new DrillKnockoutService(rooms);

    private static GameIdentity player(long id) {
        return new GameIdentity(id, "u" + id, "成员" + id, "PLAYER");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> match(Map<String, Object> view, String matchId) {
        for (Object roundValue : (List<?>) view.get("rounds")) {
            for (Object matchValue : (List<?>) roundValue) {
                Map<String, Object> row = asMap(matchValue);
                if (matchId.equals(row.get("id"))) {
                    return row;
                }
            }
        }
        return null;
    }

    private String finishedTournament() {
        return finishedTournament(8);
    }

    /** 指定人数打满 15 轮排位赛，返回已结束的锦标赛房间。 */
    private String finishedTournament(int players) {
        String roomId = (String) rooms.create(player(1), "TOURNAMENT", "TOURNAMENT").get("id");
        for (int index = 2; index <= players; index += 1) {
            rooms.join(player(index), roomId);
        }
        for (int index = 1; index <= players; index += 1) {
            rooms.ready(player(index), roomId);
        }
        for (int round = 1; round <= 15; round += 1) {
            for (int index = 1; index <= players; index += 1) {
                rooms.requestSkip(player(index), roomId);
            }
            for (int index = 1; index <= players; index += 1) {
                rooms.report(player(index), roomId, round, 100, index <= 4 ? "WIN" : "LOSS", null);
            }
        }
        return roomId;
    }

    private static void playGame(
            DrillKnockoutService knockout, String bracketId, String matchId, GameIdentity winner,
            GameIdentity loser, int game) {
        knockout.report(winner, bracketId, matchId, new DrillKnockoutService.GameReport(game, 9, 1, 0, 100, 8));
        knockout.report(loser, bracketId, matchId, new DrillKnockoutService.GameReport(game, 3, 7, 0, 40, 2));
    }

    private static void playMatch(
            DrillKnockoutService knockout, String bracketId, String matchId, GameIdentity winner,
            GameIdentity loser, int wins) {
        for (int game = 1; game <= wins; game += 1) {
            playGame(knockout, bracketId, matchId, winner, loser, game);
        }
    }

    /** 对手是机器人教练时，真人只报自己这一侧，服务端自动补机器人战绩。 */
    private static void playHumanMatch(
            DrillKnockoutService knockout, String bracketId, String matchId, GameIdentity human,
            int wins) {
        for (int game = 1; game <= wins; game += 1) {
            knockout.report(human, bracketId, matchId,
                    new DrillKnockoutService.GameReport(game, 9, 1, 0, 100, 8));
        }
    }

    @Test
    void 不足八人时机器人补位() {
        String roomId = finishedTournament(2);
        Map<String, Object> bracket = knockout.start(player(1), roomId);
        String bracketId = (String) bracket.get("id");
        List<?> quarter = (List<?>) ((List<?>) bracket.get("rounds")).get(0);
        assertEquals(4, quarter.size());
        assertEquals("u1", asMap(asMap(quarter.get(0)).get("playerA")).get("userId"));
        assertEquals("bot-6", asMap(asMap(quarter.get(0)).get("playerB")).get("userId"));
        // 两个机器人对局直接按种子序晋级
        assertEquals("bot-2", asMap(quarter.get(1)).get("winner"));
        assertEquals("bot-1", asMap(quarter.get(2)).get("winner"));
        assertEquals(null, asMap(quarter.get(3)).get("winner"));

        playHumanMatch(knockout, bracketId, (String) asMap(quarter.get(0)).get("id"), player(1), 2);
        playHumanMatch(knockout, bracketId, (String) asMap(quarter.get(3)).get("id"), player(2), 2);

        Map<String, Object> view = knockout.state(player(1), bracketId);
        assertEquals(2, ((List<?>) ((List<?>) view.get("rounds")).get(1)).size());

        List<String> ids = knockout.matchIds(bracketId);
        playHumanMatch(knockout, bracketId, ids.get(4), player(1), 3);
        playHumanMatch(knockout, bracketId, ids.get(5), player(2), 3);
        ids = knockout.matchIds(bracketId);
        assertEquals(7, ids.size());
        playMatch(knockout, bracketId, ids.get(6), player(1), player(2), 3);

        Map<String, Object> finalView = knockout.state(player(1), bracketId);
        assertEquals("u1", finalView.get("champion"));
        List<?> chats = (List<?>) rooms.state(player(1), roomId).get("chats");
        assertTrue(chats.stream()
                .map(entry -> String.valueOf(asMap(entry).get("text")))
                .anyMatch(text -> text.contains("机器人教练")));
    }

    @Test
    void 排位结束后按名次播种八强() {
        String roomId = finishedTournament();
        Map<String, Object> bracket = knockout.start(player(1), roomId);
        String bracketId = (String) bracket.get("id");

        assertEquals("RUNNING", bracket.get("status"));
        assertEquals(3, ((List<?>) bracket.get("rounds")).size());
        List<?> quarter = (List<?>) ((List<?>) bracket.get("rounds")).get(0);
        assertEquals(4, quarter.size());
        assertEquals(0, ((List<?>) ((List<?>) bracket.get("rounds")).get(1)).size());

        Map<String, Object> first = asMap(quarter.get(0));
        assertEquals("u1", asMap(first.get("playerA")).get("userId"));
        assertEquals("u8", asMap(first.get("playerB")).get("userId"));
        Map<String, Object> second = asMap(quarter.get(1));
        assertEquals("u4", asMap(second.get("playerA")).get("userId"));
        assertEquals("u5", asMap(second.get("playerB")).get("userId"));
        assertTrue(((Number) first.get("seed")).longValue() != 0);

        List<?> chats = (List<?>) rooms.state(player(1), roomId).get("chats");
        assertTrue(chats.stream()
                .map(entry -> String.valueOf(asMap(entry).get("text")))
                .anyMatch(text -> text.contains("淘汰赛开赛")));
        assertTrue(chats.stream()
                .map(entry -> String.valueOf(asMap(entry).get("text")))
                .anyMatch(text -> text.contains("第 1 局备战 35s")
                        && text.contains("等级/装备/配额已保留")));

        // 同一房间重复开赛返回同一届
        assertEquals(bracketId, knockout.start(player(2), roomId).get("id"));
    }

    @Test
    void 八进四BO3先到两局晋级并生成四强() {
        String roomId = finishedTournament();
        String bracketId = (String) knockout.start(player(1), roomId).get("id");
        Map<String, Object> view = knockout.latest(player(1), roomId);
        String q1 = (String) asMap(((List<?>) ((List<?>) view.get("rounds")).get(0)).get(0)).get("id");

        playGame(knockout, bracketId, q1, player(1), player(8), 1);
        view = knockout.state(player(1), bracketId);
        assertEquals(1, match(view, q1).get("winsA"));
        assertEquals(2, match(view, q1).get("game"));
        List<?> chats = (List<?>) rooms.state(player(1), roomId).get("chats");
        assertTrue(chats.stream()
                .map(entry -> String.valueOf(asMap(entry).get("text")))
                .anyMatch(text -> text.contains("第 2 局备战 25s")));

        playGame(knockout, bracketId, q1, player(1), player(8), 2);
        view = knockout.state(player(1), bracketId);
        assertEquals("u1", match(view, q1).get("winner"));

        // 只打完一场四分之一决赛，四强还凑不齐
        assertEquals(0, ((List<?>) ((List<?>) view.get("rounds")).get(1)).size());

        String q2 = (String) asMap(((List<?>) ((List<?>) view.get("rounds")).get(0)).get(1)).get("id");
        playMatch(knockout, bracketId, q2, player(4), player(5), 2);
        view = knockout.state(player(1), bracketId);
        List<?> semi = (List<?>) ((List<?>) view.get("rounds")).get(1);
        assertEquals(1, semi.size());
        Map<String, Object> semiFirst = asMap(semi.get(0));
        assertEquals("u1", asMap(semiFirst.get("playerA")).get("userId"));
        assertEquals("u4", asMap(semiFirst.get("playerB")).get("userId"));
        assertEquals(5, semiFirst.get("bestOf"));
    }

    @Test
    void 四强与决赛BO5决出冠军() {
        String roomId = finishedTournament();
        String bracketId = (String) knockout.start(player(1), roomId).get("id");
        List<String> ids = knockout.matchIds(bracketId);
        assertEquals(4, ids.size());

        playMatch(knockout, bracketId, ids.get(0), player(1), player(8), 2);
        playMatch(knockout, bracketId, ids.get(1), player(4), player(5), 2);
        playMatch(knockout, bracketId, ids.get(2), player(3), player(6), 2);
        playMatch(knockout, bracketId, ids.get(3), player(2), player(7), 2);

        ids = knockout.matchIds(bracketId);
        assertEquals(6, ids.size());
        playMatch(knockout, bracketId, ids.get(4), player(1), player(4), 3);
        playMatch(knockout, bracketId, ids.get(5), player(3), player(2), 3);

        ids = knockout.matchIds(bracketId);
        assertEquals(7, ids.size());
        playMatch(knockout, bracketId, ids.get(6), player(1), player(3), 3);

        Map<String, Object> view = knockout.state(player(1), bracketId);
        assertEquals("FINISHED", view.get("status"));
        assertEquals("u1", view.get("champion"));
        Map<String, Object> finalMatch = match(view, ids.get(6));
        assertEquals(3, finalMatch.get("winsA"));
        assertEquals(5, finalMatch.get("bestOf"));

        List<?> chats = (List<?>) rooms.state(player(1), roomId).get("chats");
        assertTrue(chats.stream()
                .map(entry -> String.valueOf(asMap(entry).get("text")))
                .anyMatch(text -> text.contains("夺得冠军")));
    }

    @Test
    void 非参赛者不能上报且局次必须一致() {
        String roomId = finishedTournament();
        String bracketId = (String) knockout.start(player(1), roomId).get("id");
        List<?> quarter = (List<?>) ((List<?>) knockout.state(player(1), bracketId).get("rounds")).get(0);
        String q1 = (String) asMap(quarter.get(0)).get("id");

        assertThrows(GameException.class, () -> knockout.report(
                player(9), bracketId, q1, new DrillKnockoutService.GameReport(1, 9, 1, 0, 100, 8)));
        assertThrows(GameException.class, () -> knockout.report(
                player(1), bracketId, q1, new DrillKnockoutService.GameReport(2, 9, 1, 0, 100, 8)));
        assertNotNull(match(knockout.state(player(1), bracketId), q1));
    }

    @Test
    void 排位未结束时不能开淘汰赛() {
        String roomId = (String) rooms.create(player(1), "TOURNAMENT", "TOURNAMENT").get("id");
        for (int index = 2; index <= 8; index += 1) {
            rooms.join(player(index), roomId);
        }
        assertThrows(GameException.class, () -> knockout.start(player(1), roomId));
    }
}
