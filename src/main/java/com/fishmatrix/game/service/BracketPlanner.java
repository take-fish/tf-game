package com.fishmatrix.game.service;

import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.TournamentPlayerRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 对阵编排：
 * - 2 的幂：随机单败淘汰树（2/4/8/16）。
 * - 非 2 的幂：先积分排位（3-5 人单循环，6 人以上瑞士轮 3 轮，均为 BO1），
 *   取前三后执行“三打二、胜者打一”的阶梯决赛。
 */
public final class BracketPlanner {

    public static final String STAGE_KNOCKOUT = "KNOCKOUT";
    public static final String STAGE_LEAGUE = "LEAGUE";
    public static final String STAGE_LADDER = "LADDER";

    public static final String STATUS_READY = "READY";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_BYE = "BYE";

    private BracketPlanner() {
    }

    public static boolean isPowerOfTwo(int value) {
        return value >= 2 && (value & (value - 1)) == 0;
    }

    public static int knockoutRounds(int players) {
        return Integer.numberOfTrailingZeros(players);
    }

    public static int leagueRounds(int players) {
        if (players <= 5) {
            return players % 2 == 0 ? players - 1 : players;
        }
        return 3;
    }

    public static List<MatchRecord> planKnockout(
            String tournamentId, List<TournamentPlayerRecord> players, long now) {
        List<TournamentPlayerRecord> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);
        int rounds = knockoutRounds(shuffled.size());

        List<List<String>> idsByRound = new ArrayList<>();
        for (int round = 1; round <= rounds; round += 1) {
            int count = shuffled.size() >> round;
            List<String> ids = new ArrayList<>(count);
            for (int slot = 0; slot < count; slot += 1) {
                ids.add(UUID.randomUUID().toString());
            }
            idsByRound.add(ids);
        }

        List<MatchRecord> matches = new ArrayList<>();
        for (int round = 1; round <= rounds; round += 1) {
            int count = shuffled.size() >> round;
            for (int slot = 0; slot < count; slot += 1) {
                String nextMatchId = round < rounds ? idsByRound.get(round).get(slot / 2) : null;
                String p1 = null;
                String p1n = null;
                String p2 = null;
                String p2n = null;
                if (round == 1) {
                    TournamentPlayerRecord first = shuffled.get(slot * 2);
                    TournamentPlayerRecord second = shuffled.get(slot * 2 + 1);
                    p1 = first.userId();
                    p1n = first.nickname();
                    p2 = second.userId();
                    p2n = second.nickname();
                }
                matches.add(new MatchRecord(
                        idsByRound.get(round - 1).get(slot),
                        tournamentId, round, slot + 1, STAGE_KNOCKOUT,
                        round == 1 ? STATUS_READY : STATUS_PENDING,
                        p1, p1n, p2, p2n, 0, 0, null, nextMatchId,
                        now, null, null));
            }
        }
        return matches;
    }

    /** 3-5 人单循环，额外的一人轮空；返回全部轮次。 */
    public static List<MatchRecord> planRoundRobin(
            String tournamentId, List<TournamentPlayerRecord> players, long now) {
        List<TournamentPlayerRecord> rotation = new ArrayList<>(players);
        if (rotation.size() % 2 == 1) {
            rotation.add(null);
        }
        int rounds = rotation.size() - 1;
        List<MatchRecord> matches = new ArrayList<>();
        for (int round = 1; round <= rounds; round += 1) {
            int slot = 1;
            for (int i = 0; i < rotation.size() / 2; i += 1) {
                TournamentPlayerRecord first = rotation.get(i);
                TournamentPlayerRecord second = rotation.get(rotation.size() - 1 - i);
                matches.add(pair(tournamentId, round, slot++, first, second, now));
            }
            TournamentPlayerRecord last = rotation.remove(rotation.size() - 1);
            rotation.add(1, last);
        }
        return matches;
    }

    /** 6 人以上瑞士轮第一轮；后续轮次由赛事服务按积分动态配对。 */
    public static List<MatchRecord> planSwissFirstRound(
            String tournamentId, List<TournamentPlayerRecord> players, long now) {
        List<TournamentPlayerRecord> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);
        List<MatchRecord> matches = new ArrayList<>();
        int slot = 1;
        for (int i = 0; i + 1 < shuffled.size(); i += 2) {
            matches.add(pair(tournamentId, 1, slot++, shuffled.get(i), shuffled.get(i + 1), now));
        }
        if (shuffled.size() % 2 == 1) {
            matches.add(bye(tournamentId, 1, slot, shuffled.get(shuffled.size() - 1), now));
        }
        return matches;
    }

    /**
     * 阶梯决赛：三号先挑战二号，胜者再挑战一号。
     * challengeRound 为积分赛结束后的第一轮，final 在下一轮。
     */
    public static List<MatchRecord> planLadder(
            String tournamentId, List<TournamentPlayerRecord> topThree,
            int challengeRound, long now) {
        if (topThree.size() < 3) {
            throw new IllegalArgumentException("阶梯决赛至少需要三名选手");
        }
        String finalId = UUID.randomUUID().toString();
        String challengeId = UUID.randomUUID().toString();
        TournamentPlayerRecord first = topThree.get(0);
        TournamentPlayerRecord second = topThree.get(1);
        TournamentPlayerRecord third = topThree.get(2);
        MatchRecord challenge = new MatchRecord(
                challengeId, tournamentId, challengeRound, 1, STAGE_LADDER, STATUS_READY,
                third.userId(), third.nickname(), second.userId(), second.nickname(),
                0, 0, null, finalId, now, null, null);
        MatchRecord finalMatch = new MatchRecord(
                finalId, tournamentId, challengeRound + 1, 1, STAGE_LADDER, STATUS_PENDING,
                first.userId(), first.nickname(), null, null,
                0, 0, null, null, now, null, null);
        return List.of(challenge, finalMatch);
    }

    public static MatchRecord pair(String tournamentId, int round, int slot,
                                   TournamentPlayerRecord first,
                                   TournamentPlayerRecord second, long now) {
        if (first == null && second == null) {
            throw new IllegalArgumentException("对阵双方不能同时为空");
        }
        if (first == null) {
            return bye(tournamentId, round, slot, second, now);
        }
        if (second == null) {
            return bye(tournamentId, round, slot, first, now);
        }
        return new MatchRecord(
                UUID.randomUUID().toString(), tournamentId, round, slot, STAGE_LEAGUE,
                round == 1 ? STATUS_READY : STATUS_PENDING,
                first.userId(), first.nickname(), second.userId(), second.nickname(),
                0, 0, null, null, now, null, null);
    }

    public static MatchRecord bye(String tournamentId, int round, int slot,
                                  TournamentPlayerRecord player, long now) {
        return new MatchRecord(
                UUID.randomUUID().toString(), tournamentId, round, slot, STAGE_LEAGUE,
                STATUS_BYE, player.userId(), player.nickname(), null, null,
                0, 0, player.userId(), null, now, now, now);
    }

    /** 淘汰赛：奇数 slot 进下一轮 player1，偶数 slot 进 player2。 */
    public static boolean nextSlotIsPlayer1(int currentSlot) {
        return currentSlot % 2 == 1;
    }
}
