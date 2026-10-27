package com.fishmatrix.game.model;

import java.util.Set;

/**
 * 游戏类型标识，与 game_tournament.game_id 对应。
 */
public final class GameIds {

    public static final String HEATMAP_GOMOKU = "heatmap-gomoku";
    public static final String BLIND_XIANGQI = "blind-xiangqi";
    public static final Set<String> ALL = Set.of(HEATMAP_GOMOKU, BLIND_XIANGQI);

    private GameIds() {
    }
}
