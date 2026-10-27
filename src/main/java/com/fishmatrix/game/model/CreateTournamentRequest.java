package com.fishmatrix.game.model;

import java.util.List;

/**
 * 创建赛事：gameId 缺省为五子棋；盲棋需选择 blindLayout 摆法。
 */
public record CreateTournamentRequest(
        String name,
        String format,
        List<PlayerRef> players,
        String gameId,
        String blindLayout) {

    /** 兼容旧调用：默认五子棋。 */
    public CreateTournamentRequest(String name, String format, List<PlayerRef> players) {
        this(name, format, players, null, null);
    }
}
