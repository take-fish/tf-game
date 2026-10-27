package com.fishmatrix.game.model;

import com.fishmatrix.game.arena.ArenaGames;
import java.util.List;

/**
 * 创建赛事。gameId 决定演练剧本，缺省由系统随机抽取；盲棋需选择 blindLayout 摆法。
 */
public record CreateTournamentRequest(
        String name,
        String format,
        List<PlayerRef> players,
        String gameId,
        String blindLayout) {

    /** 兼容旧调用：默认系统随机剧本。 */
    public CreateTournamentRequest(String name, String format, List<PlayerRef> players) {
        this(name, format, players, ArenaGames.RANDOM, null);
    }
}
