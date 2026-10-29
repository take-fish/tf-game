package com.fishmatrix.game.service;

/**
 * 排行榜计分规则。
 *
 * <p>大榜只认赛事头衔：冠军 30 分、亚军 10 分；分游戏榜按胜场与冠军折算。
 * 规则放在这里，持久层只接收算好的增量，避免业务判断下沉到 store。</p>
 */
public final class PlayerStatRules {

    public static final int CHAMPION_POINTS = 30;
    public static final int RUNNER_UP_POINTS = 10;
    public static final int GAME_WIN_POINTS = 3;
    public static final int GAME_LOSS_POINTS = 1;
    public static final int GAME_CHAMPION_POINTS = 10;

    private PlayerStatRules() {
    }

    /** 赛事头衔折算的大榜积分。 */
    public static int titlePoints(boolean champion) {
        return champion ? CHAMPION_POINTS : RUNNER_UP_POINTS;
    }

    /** 分游戏榜单场积分：胜 3、负 1，夺冠额外 +10。 */
    public static int gameMatchPoints(boolean win, boolean champion) {
        return (win ? GAME_WIN_POINTS : GAME_LOSS_POINTS)
                + (champion ? GAME_CHAMPION_POINTS : 0);
    }
}
