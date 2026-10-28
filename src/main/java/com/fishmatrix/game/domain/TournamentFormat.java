package com.fishmatrix.game.domain;

import com.baomidou.mybatisplus.annotation.IEnum;

/** 赛制：BO1 / BO3 / BO5。 */
public enum TournamentFormat implements IEnum<String> {
    BO1,
    BO3,
    BO5;

    @Override
    public String getValue() {
        return name();
    }
}
