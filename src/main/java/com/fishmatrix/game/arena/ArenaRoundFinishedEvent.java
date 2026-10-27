package com.fishmatrix.game.arena;

/**
 * 竞技场小局结算事件。由 MatchService 监听，复用 BO 计分、休息与赛事晋级流程。
 */
public record ArenaRoundFinishedEvent(
        String matchId,
        String roundId,
        String winnerUserId,
        String reason) {
}
