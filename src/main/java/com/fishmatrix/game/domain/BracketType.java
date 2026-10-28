package com.fishmatrix.game.domain;

import com.baomidou.mybatisplus.annotation.IEnum;

/** 赛程编排方式：创建时待定，开赛后落为淘汰、积分、阶梯或页面淘汰。 */
public enum BracketType implements IEnum<String> {
    TBD,
    KNOCKOUT,
    LEAGUE,
    LADDER,
    PAGE_PLAYOFF;

    @Override
    public String getValue() {
        return name();
    }
}
