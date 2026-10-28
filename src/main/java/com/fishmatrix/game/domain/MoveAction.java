package com.fishmatrix.game.domain;

import com.baomidou.mybatisplus.annotation.IEnum;

/** 棋谱动作：五子棋落子、盲棋翻子与走子。 */
public enum MoveAction implements IEnum<String> {
    PLACE,
    REVEAL,
    MOVE;

    @Override
    public String getValue() {
        return name();
    }
}
