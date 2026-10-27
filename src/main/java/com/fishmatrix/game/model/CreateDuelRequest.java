package com.fishmatrix.game.model;

import com.fishmatrix.game.arena.ArenaGames;

/**
 * 一对一约战：指定对手、赛制与演练剧本，盲棋还需指定摆法；发起者自动作为另一方参赛。
 */
public record CreateDuelRequest(
        String opponentUserId,
        String opponentNickname,
        String format,
        String gameId,
        String blindLayout) {

    /** 兼容旧调用：默认五子棋。 */
    public CreateDuelRequest(String opponentUserId, String opponentNickname, String format) {
        this(opponentUserId, opponentNickname, format, ArenaGames.GOMOKU, null);
    }
}
