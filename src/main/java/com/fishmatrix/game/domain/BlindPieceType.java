package com.fishmatrix.game.domain;

import com.baomidou.mybatisplus.annotation.IEnum;

/** 盲棋兵种：帅、仕、象、马、车、炮、兵。 */
public enum BlindPieceType implements IEnum<String> {
    GENERAL,
    ADVISOR,
    ELEPHANT,
    HORSE,
    CHARIOT,
    CANNON,
    SOLDIER;

    @Override
    public String getValue() {
        return name();
    }
}
