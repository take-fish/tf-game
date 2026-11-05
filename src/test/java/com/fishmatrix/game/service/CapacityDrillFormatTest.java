package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 容量编排赛制按赛段固定：八强 BO3，四强与决赛 BO5，积分赛 BO1。
 */
class CapacityDrillFormatTest {

    @Test
    void quarterfinalAndEarlierAreBestOfThree() {
        assertThat(TournamentRules.capacityDrillMaxGames(
                BracketPlanner.STAGE_KNOCKOUT, 1, 3)).isEqualTo(3);
        assertThat(TournamentRules.capacityDrillMaxGames(
                BracketPlanner.STAGE_KNOCKOUT, 3, 5)).isEqualTo(3);
        assertThat(TournamentRules.capacityDrillMaxGames(
                BracketPlanner.STAGE_KNOCKOUT, 1, 4)).isEqualTo(3);
        assertThat(TournamentRules.capacityDrillMaxGames(
                BracketPlanner.STAGE_KNOCKOUT, 2, 4)).isEqualTo(3);
    }

    @Test
    void semifinalAndFinalAreBestOfFive() {
        assertThat(TournamentRules.capacityDrillMaxGames(
                BracketPlanner.STAGE_KNOCKOUT, 2, 3)).isEqualTo(5);
        assertThat(TournamentRules.capacityDrillMaxGames(
                BracketPlanner.STAGE_KNOCKOUT, 3, 3)).isEqualTo(5);
        assertThat(TournamentRules.capacityDrillMaxGames(
                BracketPlanner.STAGE_KNOCKOUT, 3, 4)).isEqualTo(5);
        assertThat(TournamentRules.capacityDrillMaxGames(
                BracketPlanner.STAGE_KNOCKOUT, 4, 4)).isEqualTo(5);
    }

    @Test
    void shortBracketsAreBestOfFiveThroughout() {
        assertThat(TournamentRules.capacityDrillMaxGames(
                BracketPlanner.STAGE_KNOCKOUT, 1, 1)).isEqualTo(5);
        assertThat(TournamentRules.capacityDrillMaxGames(
                BracketPlanner.STAGE_KNOCKOUT, 1, 2)).isEqualTo(5);
        assertThat(TournamentRules.capacityDrillMaxGames(
                BracketPlanner.STAGE_KNOCKOUT, 2, 2)).isEqualTo(5);
    }

    @Test
    void leagueStaysSingleGame() {
        assertThat(TournamentRules.capacityDrillMaxGames(
                BracketPlanner.STAGE_LEAGUE, 1, 1)).isEqualTo(1);
        assertThat(TournamentRules.capacityDrillMaxGames(
                BracketPlanner.STAGE_LEAGUE, 3, 3)).isEqualTo(1);
    }

    @Test
    void pagePlayoffUsesTheSameRemainingRoundsRule() {
        assertThat(TournamentRules.capacityDrillMaxGames(
                BracketPlanner.STAGE_PAGE_PLAYOFF, 3, 5)).isEqualTo(3);
        assertThat(TournamentRules.capacityDrillMaxGames(
                BracketPlanner.STAGE_PAGE_PLAYOFF, 4, 5)).isEqualTo(5);
        assertThat(TournamentRules.capacityDrillMaxGames(
                BracketPlanner.STAGE_PAGE_PLAYOFF, 5, 5)).isEqualTo(5);
    }
}
