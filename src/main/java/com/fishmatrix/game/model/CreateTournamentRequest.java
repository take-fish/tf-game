package com.fishmatrix.game.model;

import com.fishmatrix.game.arena.ArenaGames;
import java.util.List;

/**
 * 创建赛事。gameId 决定进入哪个演练剧本，缺省仍为原来的热力五子棋。
 */
public record CreateTournamentRequest(
        String name,
        String format,
        List<PlayerRef> players,
        String gameId) {

    public CreateTournamentRequest(String name, String format, List<PlayerRef> players) {
        this(name, format, players, ArenaGames.GOMOKU);
    }
}
