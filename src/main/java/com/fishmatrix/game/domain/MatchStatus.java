package com.fishmatrix.game.domain;

import com.baomidou.mybatisplus.annotation.IEnum;

/** 一场 BO 对局的状态：待激活、就绪、进行中、休息、结束与轮空。 */
public enum MatchStatus implements IEnum<String> {
    PENDING,
    READY,
    RUNNING,
    REST,
    FINISHED,
    BYE;

    @Override
    public String getValue() {
        return name();
    }
}
