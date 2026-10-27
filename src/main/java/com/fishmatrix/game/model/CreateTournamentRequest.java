package com.fishmatrix.game.model;

import com.fishmatrix.game.arena.ArenaGames;
import java.util.List;

/**
 * 创建赛事。
 * - gameId 为具体剧本：锦标赛，全程固定该游戏；
 * - gameId 为 random：S赛，每场对局从 gamePool 启用池中随机抽取（缺省为全部 1v1 游戏）。
 * 盲棋需选择 blindLayout 摆法；S赛启用盲棋时同样使用该摆法。
 */
public record CreateTournamentRequest(
        String name,
        String format,
        List<PlayerRef> players,
        String gameId,
        String blindLayout,
        List<String> gamePool) {

    /** 兼容旧调用：S赛，启用池缺省为全部 1v1 游戏。 */
    public CreateTournamentRequest(String name, String format, List<PlayerRef> players,
                                   String gameId, String blindLayout) {
        this(name, format, players, gameId, blindLayout, null);
    }

    /** 兼容旧调用：默认系统随机剧本。 */
    public CreateTournamentRequest(String name, String format, List<PlayerRef> players) {
        this(name, format, players, ArenaGames.RANDOM, null, null);
    }
}
