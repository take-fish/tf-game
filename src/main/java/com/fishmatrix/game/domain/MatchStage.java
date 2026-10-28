package com.fishmatrix.game.domain;

import com.baomidou.mybatisplus.annotation.IEnum;

/** 对阵阶段：积分/瑞士轮、单败淘汰、阶梯赛与页面淘汰赛。 */
public enum MatchStage implements IEnum<String> {
    LEAGUE,
    KNOCKOUT,
    LADDER,
    PAGE_PLAYOFF;

    @Override
    public String getValue() {
        return name();
    }
}
