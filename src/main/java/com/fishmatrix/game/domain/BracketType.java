package com.fishmatrix.game.domain;

import com.baomidou.mybatisplus.annotation.IEnum;

/** 赛程编排方式：创建时待定，开赛后落为淘汰、积分、阶梯、页面淘汰或良跑跑方案赛。 */
public enum BracketType implements IEnum<String> {
    TBD,
    KNOCKOUT,
    LEAGUE,
    LADDER,
    PAGE_PLAYOFF,
    /** 良跑跑方案：15 轮排位赛 + 八强 BO3 / 四强决赛 BO5，由演练房承载。 */
    DRILL;

    @Override
    public String getValue() {
        return name();
    }
}
