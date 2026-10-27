package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.TournamentPlayerRecord;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BracketPlannerTest {

    @Test
    void recognisesPowerOfTwo() {
        assertThat(BracketPlanner.isPowerOfTwo(2)).isTrue();
        assertThat(BracketPlanner.isPowerOfTwo(16)).isTrue();
        assertThat(BracketPlanner.isPowerOfTwo(6)).isFalse();
        assertThat(BracketPlanner.isPowerOfTwo(1)).isFalse();
        assertThat(BracketPlanner.knockoutRounds(16)).isEqualTo(4);
    }

    @Test
    void plansCompleteKnockoutTree() {
        List<MatchRecord> matches = BracketPlanner.planKnockout("t1", players(8), 1_000L);

        assertThat(matches).hasSize(7);
        assertThat(matches.stream().filter(match -> match.roundNo() == 1).count()).isEqualTo(4);
        assertThat(matches.stream().filter(match -> match.roundNo() == 2).count()).isEqualTo(2);
        assertThat(matches.stream().filter(match -> match.roundNo() == 3).count()).isEqualTo(1);
        assertThat(matches.stream()
                .filter(match -> match.roundNo() == 1)
                .allMatch(match -> BracketPlanner.STATUS_READY.equals(match.status()))).isTrue();
        assertThat(matches.stream()
                .filter(match -> match.roundNo() > 1)
                .allMatch(match -> BracketPlanner.STATUS_PENDING.equals(match.status())
                        && match.player1UserId() == null)).isTrue();
        assertThat(matches.stream()
                .filter(match -> match.roundNo() < 3)
                .allMatch(match -> match.nextMatchId() != null)).isTrue();
    }

    @Test
    void roundRobinCoversEveryPairOnceForThreePlayers() {
        List<MatchRecord> matches = BracketPlanner.planRoundRobin("t1", players(3), 1_000L);
        Set<String> realPairs = new HashSet<>();
        for (MatchRecord match : matches) {
            if (!BracketPlanner.STATUS_BYE.equals(match.status())) {
                realPairs.add(pairKey(match.player1UserId(), match.player2UserId()));
            }
        }

        assertThat(realPairs).containsExactlyInAnyOrder("u1|u2", "u1|u3", "u2|u3");
        assertThat(matches.stream()
                .filter(match -> BracketPlanner.STATUS_BYE.equals(match.status())).count())
                .isEqualTo(3);
    }

    @Test
    void swissFirstRoundHandlesOddPlayerCount() {
        List<MatchRecord> matches = BracketPlanner.planSwissFirstRound("t1", players(7), 1_000L);

        assertThat(matches).hasSize(4);
        assertThat(matches.stream()
                .filter(match -> BracketPlanner.STATUS_BYE.equals(match.status())).count())
                .isEqualTo(1);
        assertThat(matches.stream()
                .filter(match -> BracketPlanner.STATUS_READY.equals(match.status())).count())
                .isEqualTo(3);
    }

    @Test
    void ladderStartsThirdAgainstSecondThenWinnerChallengesFirst() {
        List<MatchRecord> matches = BracketPlanner.planLadder("t1", players(5).subList(0, 3), 4, 1_000L);

        MatchRecord challenge = matches.get(0);
        MatchRecord finalMatch = matches.get(1);
        assertThat(challenge.stage()).isEqualTo(BracketPlanner.STAGE_LADDER);
        assertThat(challenge.status()).isEqualTo(BracketPlanner.STATUS_READY);
        assertThat(challenge.player1UserId()).isEqualTo("u3");
        assertThat(challenge.player2UserId()).isEqualTo("u2");
        assertThat(challenge.nextMatchId()).isEqualTo(finalMatch.id());
        assertThat(finalMatch.status()).isEqualTo(BracketPlanner.STATUS_PENDING);
        assertThat(finalMatch.player1UserId()).isEqualTo("u1");
        assertThat(finalMatch.player2UserId()).isNull();
        assertThat(finalMatch.roundNo()).isEqualTo(5);
    }

    @Test
    void pagePlayoffPairsThirdAgainstFourthThenSecondThenFirst() {
        List<MatchRecord> matches =
                BracketPlanner.planPagePlayoff("t1", players(4), 3, 1_000L);

        assertThat(matches).hasSize(3);
        MatchRecord opener = matches.get(0);
        MatchRecord challenge = matches.get(1);
        MatchRecord finalMatch = matches.get(2);

        assertThat(opener.stage()).isEqualTo(BracketPlanner.STAGE_PAGE_PLAYOFF);
        assertThat(opener.roundNo()).isEqualTo(3);
        assertThat(opener.status()).isEqualTo(BracketPlanner.STATUS_READY);
        assertThat(opener.player1UserId()).isEqualTo("u3");
        assertThat(opener.player2UserId()).isEqualTo("u4");
        // 三号拥有首场选图权，四号没有。
        assertThat(opener.gameChooserUserId()).isEqualTo("u3");
        assertThat(opener.nextMatchId()).isEqualTo(challenge.id());

        assertThat(challenge.roundNo()).isEqualTo(4);
        assertThat(challenge.status()).isEqualTo(BracketPlanner.STATUS_PENDING);
        assertThat(challenge.player1UserId()).isEqualTo("u2");
        assertThat(challenge.player2UserId()).isNull();
        assertThat(challenge.gameChooserUserId()).isEqualTo("u2");
        assertThat(challenge.nextMatchId()).isEqualTo(finalMatch.id());

        assertThat(finalMatch.roundNo()).isEqualTo(5);
        assertThat(finalMatch.status()).isEqualTo(BracketPlanner.STATUS_PENDING);
        assertThat(finalMatch.player1UserId()).isEqualTo("u1");
        assertThat(finalMatch.player2UserId()).isNull();
        assertThat(finalMatch.gameChooserUserId()).isEqualTo("u1");
        assertThat(finalMatch.nextMatchId()).isNull();
        // 首局剧本由服务层抽签后写入，编排阶段统一为空。
        assertThat(matches).allMatch(match -> match.firstGameId() == null);
    }

    @Test
    void pagePlayoffWithThreeQualifiersStartsThirdAgainstSecond() {
        List<MatchRecord> matches =
                BracketPlanner.planPagePlayoff("t1", players(3), 4, 1_000L);

        assertThat(matches).hasSize(2);
        MatchRecord opener = matches.get(0);
        MatchRecord finalMatch = matches.get(1);

        assertThat(opener.player1UserId()).isEqualTo("u3");
        assertThat(opener.player2UserId()).isEqualTo("u2");
        assertThat(opener.gameChooserUserId()).isEqualTo("u3");
        assertThat(opener.roundNo()).isEqualTo(4);
        assertThat(opener.nextMatchId()).isEqualTo(finalMatch.id());

        assertThat(finalMatch.player1UserId()).isEqualTo("u1");
        assertThat(finalMatch.player2UserId()).isNull();
        assertThat(finalMatch.gameChooserUserId()).isEqualTo("u1");
        assertThat(finalMatch.roundNo()).isEqualTo(5);
        assertThat(BracketPlanner.pagePlayoffRounds(3)).isEqualTo(2);
        assertThat(BracketPlanner.pagePlayoffRounds(4)).isEqualTo(3);
    }

    @Test
    void leagueRoundCountsMatchPlayerCount() {
        assertThat(BracketPlanner.leagueRounds(3)).isEqualTo(3);
        assertThat(BracketPlanner.leagueRounds(4)).isEqualTo(3);
        assertThat(BracketPlanner.leagueRounds(5)).isEqualTo(5);
        assertThat(BracketPlanner.leagueRounds(8)).isEqualTo(3);
    }

    private static List<TournamentPlayerRecord> players(int count) {
        List<TournamentPlayerRecord> players = new ArrayList<>();
        for (int i = 1; i <= count; i += 1) {
            players.add(new TournamentPlayerRecord(
                    "t1", "u" + i, "选手" + i, i, "ACCEPTED", 1_000L, 1_000L));
        }
        return players;
    }

    private static String pairKey(String first, String second) {
        return first.compareTo(second) < 0 ? first + "|" + second : second + "|" + first;
    }
}
