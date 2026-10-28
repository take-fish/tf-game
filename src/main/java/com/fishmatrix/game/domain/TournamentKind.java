package com.fishmatrix.game.domain;

import com.baomidou.mybatisplus.annotation.IEnum;

/** 赛事类型：正式赛事与一对一的约战。 */
public enum TournamentKind implements IEnum<String> {
    TOURNAMENT,
    DUEL;

    @Override
    public String getValue() {
        return name();
    }
}
