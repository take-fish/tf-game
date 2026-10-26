package com.fishmatrix.game.model;

/**
 * 一对一约战：只需指定对手与赛制，发起者自动作为另一方参赛。
 */
public record CreateDuelRequest(
        String opponentUserId,
        String opponentNickname,
        String format) {
}
