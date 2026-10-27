package com.fishmatrix.game.model;

import com.fishmatrix.game.arena.ArenaGames;

/**
 * 一对一约战：指定对手、赛制与演练剧本，发起者自动作为另一方参赛。
 */
public record CreateDuelRequest(
        String opponentUserId,
        String opponentNickname,
        String format,
        String gameId) {

    public CreateDuelRequest(String opponentUserId, String opponentNickname, String format) {
        this(opponentUserId, opponentNickname, format, ArenaGames.GOMOKU);
    }
}
