package com.fishmatrix.game.domain;

import com.baomidou.mybatisplus.annotation.IEnum;

/** 小局状态：对弈中、局间休息与已结束。 */
public enum RoundStatus implements IEnum<String> {
    RUNNING,
    REST,
    FINISHED;

    @Override
    public String getValue() {
        return name();
    }
}
