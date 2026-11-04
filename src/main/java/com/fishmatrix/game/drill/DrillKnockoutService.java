package com.fishmatrix.game.drill;

import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 良跑跑淘汰赛：排位赛 1–8 名进入八强，八进四 BO3、四进二 BO5、决赛 BO5。
 *
 * <p>每一「局」仍是 8 人同局：对阵双方真人 + 6 个机器人教练，客户端本地跑 10 轮；
 * 双方各报一次终局战绩，服务端按「战绩分 → SLI → 存活数」判定该局胜负，
 * 先到 2/3 局者晋级。淘汰赛状态先放内存，和演练房间同生命周期。</p>
 */
@Service
@RequiredArgsConstructor
public class DrillKnockoutService {

    public static final int PREP_SECONDS = 35;
    public static final int GAME_PREP_SECONDS = 25;
    /** 淘汰赛一局 = 一轮战斗。 */
    public static final int KNOCKOUT_ROUNDS = 1;

    private final DrillRoomService rooms;
    private final Map<String, Bracket> brackets = new ConcurrentHashMap<>();
    private final Map<String, String> byRoom = new ConcurrentHashMap<>();

    public record GameReport(int game, int wins, int losses, int draws, int sli, int survivors) {
    }

    private record Seed(String id, String name) {
    }

    static final class Match {
        final String id;
        final int round;
        final int slot;
        final int bestOf;
        String playerA;
        String playerB;
        String nicknameA = "";
        String nicknameB = "";
        int winsA;
        int winsB;
        int game = 1;
        long seed;
        long prepEndsAt;
        GameReport reportA;
        GameReport reportB;
        String winner;

        Match(String id, int round, int slot, int bestOf) {
            this.id = id;
            this.round = round;
            this.slot = slot;
            this.bestOf = bestOf;
        }

        int target() {
            return bestOf / 2 + 1;
        }
    }

    static final class Bracket {
        final String id;
        final String roomId;
        final long createdAt = System.currentTimeMillis();
        final List<List<Match>> rounds = new ArrayList<>();
        String status = "RUNNING";
        String champion;

        Bracket(String id, String roomId) {
            this.id = id;
            this.roomId = roomId;
            for (int index = 0; index < 3; index += 1) {
                rounds.add(new ArrayList<>());
            }
        }
    }

    public synchronized Map<String, Object> start(GameIdentity identity, String roomId) {
        if (!rooms.isMember(roomId, identity.userId())) {
            throw GameException.badRequest("DRILL_ROOM_NOT_MEMBER", "不在该房间中");
        }
        String existing = byRoom.get(roomId);
        if (existing != null) {
            return view(brackets.get(existing));
        }
        List<DrillRoomService.RankedPlayer> ranking = rooms.ranking(roomId);
        if (ranking.size() < 2) {
            throw GameException.badRequest(
                    "DRILL_KNOCKOUT_NEEDS_TWO", "淘汰赛至少需要 2 名选手，当前 " + ranking.size() + " 名");
        }
        List<Seed> seeds = new ArrayList<>();
        for (DrillRoomService.RankedPlayer ranked : ranking) {
            seeds.add(new Seed(ranked.userId(), ranked.nickname()));
        }
        int botCount = 8 - seeds.size();
        for (int index = 1; index <= botCount; index += 1) {
            seeds.add(new Seed("bot-" + index, "机器人教练 " + index));
        }
        Bracket bracket = new Bracket(UUID.randomUUID().toString().substring(0, 8), roomId);
        int[][] pairs = {{0, 7}, {3, 4}, {2, 5}, {1, 6}};
        List<Match> quarter = bracket.rounds.get(0);
        for (int slot = 0; slot < pairs.length; slot += 1) {
            Seed first = seeds.get(pairs[slot][0]);
            Seed second = seeds.get(pairs[slot][1]);
            Match match = new Match(bracket.id + "-q" + (slot + 1), 0, slot, 3);
            match.playerA = first.id();
            match.nicknameA = first.name();
            match.playerB = second.id();
            match.nicknameB = second.name();
            quarter.add(match);
        }
        rooms.systemSay(roomId, "淘汰赛开赛：" + quarter.stream()
                .map(match -> match.nicknameA + " vs " + match.nicknameB)
                .reduce((left, right) -> left + "，" + right)
                .orElse(""));
        for (Match match : quarter) {
            prepareMatch(bracket, match);
        }
        advance(bracket);
        brackets.put(bracket.id, bracket);
        byRoom.put(roomId, bracket.id);
        return view(bracket);
    }

    public synchronized Map<String, Object> latest(GameIdentity identity, String roomId) {
        if (!rooms.isMember(roomId, identity.userId())) {
            throw GameException.badRequest("DRILL_ROOM_NOT_MEMBER", "不在该房间中");
        }
        String bracketId = byRoom.get(roomId);
        if (bracketId == null) {
            throw GameException.notFound("淘汰赛尚未开始");
        }
        return view(brackets.get(bracketId));
    }

    public synchronized Map<String, Object> state(GameIdentity identity, String bracketId) {
        Bracket bracket = requireBracket(bracketId);
        requireParticipant(bracket, identity.userId());
        return view(bracket);
    }

    public synchronized Map<String, Object> report(
            GameIdentity identity, String bracketId, String matchId, GameReport report) {
        Bracket bracket = requireBracket(bracketId);
        Match match = requireMatch(bracket, matchId);
        requireParticipant(bracket, identity.userId());
        if (match.winner != null) {
            return view(bracket);
        }
        if (report == null || report.game() != match.game) {
            throw GameException.badRequest("DRILL_KNOCKOUT_GAME_MISMATCH", "局次不一致，请等待同步");
        }
        if (identity.userId().equals(match.playerA)) {
            if (match.reportA != null) {
                return view(bracket);
            }
            match.reportA = report;
        } else if (identity.userId().equals(match.playerB)) {
            if (match.reportB != null) {
                return view(bracket);
            }
            match.reportB = report;
        } else {
            throw GameException.badRequest("DRILL_KNOCKOUT_NOT_PLAYER", "不在该场对局中");
        }
        // 对手是机器人教练：按真人这一局的结果镜像一份战绩，立即结算
        if (isBot(match.playerA) && match.reportA == null) {
            match.reportA = botReport(match, match.reportB);
        }
        if (isBot(match.playerB) && match.reportB == null) {
            match.reportB = botReport(match, match.reportA);
        }
        if (match.reportA != null && match.reportB != null) {
            settleGame(bracket, match);
        }
        return view(bracket);
    }

    private void settleGame(Bracket bracket, Match match) {
        GameReport a = match.reportA;
        GameReport b = match.reportB;
        int scoreA = a.wins() * 3 + a.draws();
        int scoreB = b.wins() * 3 + b.draws();
        int result = Integer.compare(scoreA, scoreB);
        if (result == 0) {
            result = Integer.compare(a.sli(), b.sli());
        }
        if (result == 0) {
            result = Integer.compare(a.survivors(), b.survivors());
        }
        if (result > 0) {
            match.winsA += 1;
        } else if (result < 0) {
            match.winsB += 1;
        }
        if (result == 0) {
            rooms.systemSay(bracket.roomId,
                    "第 " + match.game + " 局战平（" + scoreA + ":" + scoreB + "），重开一局");
        } else {
            String gameWinner = result > 0 ? match.nicknameA : match.nicknameB;
            rooms.systemSay(bracket.roomId, gameWinner + " 拿下第 " + match.game + " 局，大比分 "
                    + match.winsA + ":" + match.winsB);
        }
        if (match.winsA >= match.target()) {
            match.winner = match.playerA;
        } else if (match.winsB >= match.target()) {
            match.winner = match.playerB;
        }
        if (match.winner != null) {
            boolean firstWins = match.winner.equals(match.playerA);
            String winnerName = firstWins ? match.nicknameA : match.nicknameB;
            String loserName = firstWins ? match.nicknameB : match.nicknameA;
            int winnerWins = firstWins ? match.winsA : match.winsB;
            int loserWins = firstWins ? match.winsB : match.winsA;
            String suffix = match.round == 2
                    ? "夺得冠军"
                    : "晋级" + roundName(match.round + 1);
            rooms.systemSay(bracket.roomId,
                    winnerName + " " + winnerWins + ":" + loserWins + " 击败 " + loserName + "，" + suffix);
        }
        if (match.winner == null) {
            match.game += 1;
            startGame(bracket, match, GAME_PREP_SECONDS);
        }
        advance(bracket);
    }

    private static String roundName(int round) {
        return switch (round) {
            case 1 -> "四强";
            case 2 -> "决赛";
            default -> "八强";
        };
    }

    private void advance(Bracket bracket) {
        ensureRound(bracket, 1, 2, 5);
        ensureRound(bracket, 2, 1, 5);
        List<Match> finalRound = bracket.rounds.get(2);
        if (!finalRound.isEmpty() && finalRound.get(0).winner != null) {
            bracket.status = "FINISHED";
            bracket.champion = finalRound.get(0).winner;
        }
    }

    private void ensureRound(Bracket bracket, int round, int count, int bestOf) {
        List<Match> previous = bracket.rounds.get(round - 1);
        List<Match> current = bracket.rounds.get(round);
        for (int slot = 0; slot < count; slot += 1) {
            if (current.size() > slot) {
                continue;
            }
            if (previous.size() < slot * 2 + 2) {
                return;
            }
            Match first = previous.get(slot * 2);
            Match second = previous.get(slot * 2 + 1);
            if (first.winner == null || second.winner == null) {
                return;
            }
            Match next = new Match(bracket.id + "-r" + round + "-" + (slot + 1), round, slot, bestOf);
            boolean firstAdvances = first.winner.equals(first.playerA);
            next.playerA = first.winner;
            next.nicknameA = firstAdvances ? first.nicknameA : first.nicknameB;
            boolean secondAdvances = second.winner.equals(second.playerA);
            next.playerB = second.winner;
            next.nicknameB = secondAdvances ? second.nicknameA : second.nicknameB;
            current.add(next);
            prepareMatch(bracket, next);
        }
    }

    private static boolean isBot(String userId) {
        return userId != null && userId.startsWith("bot-");
    }

    /** 机器人对机器人直接按种子序晋级；真人场次正常发 seed 等上报。 */
    private void prepareMatch(Bracket bracket, Match match) {
        if (isBot(match.playerA) && isBot(match.playerB)) {
            match.winner = match.playerA;
            match.winsA = match.target();
            match.seed = 0;
            match.prepEndsAt = 0;
            return;
        }
        startGame(bracket, match, PREP_SECONDS);
    }

    /** 一局一轮战斗：机器人按真人这一局的结果镜像，真人胜则机器人负，反之亦然。 */
    private static GameReport botReport(Match match, GameReport opponent) {
        int wins = 0;
        int losses = 0;
        int draws = 0;
        if (opponent == null) {
            losses = 1;
        } else if (opponent.wins() > opponent.losses()) {
            losses = 1;
        } else if (opponent.losses() > opponent.wins()) {
            wins = 1;
        } else {
            draws = 1;
        }
        java.util.Random random = new java.util.Random(match.seed + match.game * 131L);
        int sli = 40 + random.nextInt(61);
        int survivors = random.nextInt(9);
        return new GameReport(match.game, wins, losses, draws, sli, survivors);
    }

    private void startGame(Bracket bracket, Match match, int prepSeconds) {
        match.seed = ThreadLocalRandom.current().nextLong();
        match.prepEndsAt = System.currentTimeMillis() + prepSeconds * 1000L;
        match.reportA = null;
        match.reportB = null;
        rooms.systemSay(bracket.roomId,
                roundName(match.round) + " " + match.nicknameA + " vs " + match.nicknameB
                        + " 第 " + match.game + " 局备战 " + prepSeconds + "s（等级/装备/配额已保留）");
    }

    private Map<String, Object> view(Bracket bracket) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", bracket.id);
        view.put("roomId", bracket.roomId);
        view.put("status", bracket.status);
        view.put("champion", bracket.champion);
        view.put("serverTime", System.currentTimeMillis());
        view.put("rounds", bracket.rounds.stream().map(this::roundView).toList());
        return view;
    }

    private List<Map<String, Object>> roundView(List<Match> matches) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Match match : matches) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", match.id);
            row.put("round", match.round);
            row.put("slot", match.slot);
            row.put("bestOf", match.bestOf);
            row.put("game", match.game);
            row.put("seed", match.seed);
            row.put("prepEndsAt", match.prepEndsAt);
            row.put("playerA", playerView(match.playerA, match.nicknameA));
            row.put("playerB", playerView(match.playerB, match.nicknameB));
            row.put("winsA", match.winsA);
            row.put("winsB", match.winsB);
            row.put("reportedA", match.reportA != null);
            row.put("reportedB", match.reportB != null);
            row.put("winner", match.winner);
            row.put("phase", match.winner != null ? "FINISHED" : "PREP");
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> playerView(String userId, String nickname) {
        if (userId == null) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("userId", userId);
        row.put("nickname", nickname);
        return row;
    }

    private Bracket requireBracket(String bracketId) {
        Bracket bracket = brackets.get(bracketId);
        if (bracket == null) {
            throw GameException.notFound("淘汰赛不存在或已过期");
        }
        return bracket;
    }

    private static Match requireMatch(Bracket bracket, String matchId) {
        for (List<Match> round : bracket.rounds) {
            for (Match match : round) {
                if (match.id.equals(matchId)) {
                    return match;
                }
            }
        }
        throw GameException.notFound("对阵不存在");
    }

    private void requireParticipant(Bracket bracket, String userId) {
        for (List<Match> round : bracket.rounds) {
            for (Match match : round) {
                if (userId.equals(match.playerA) || userId.equals(match.playerB)) {
                    return;
                }
            }
        }
        throw GameException.badRequest("DRILL_KNOCKOUT_NOT_MEMBER", "不在该届淘汰赛中");
    }

    /** 测试与调试：按终局时间排序结束的场次。 */
    List<String> matchIds(String bracketId) {
        Bracket bracket = requireBracket(bracketId);
        List<String> ids = new ArrayList<>();
        for (List<Match> round : bracket.rounds) {
            for (Match match : round) {
                ids.add(match.id);
            }
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }
}
