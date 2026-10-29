package com.fishmatrix.game.domain;

import com.baomidou.mybatisplus.annotation.IEnum;

/** 盲棋棋子阵营。 */
public enum BlindColor implements IEnum<String> {
    RED,
    BLACK;

    @Override
    public String getValue() {
        return name();
    }
}
