package com.fishmatrix.game.domain;

import com.baomidou.mybatisplus.annotation.IEnum;

/** 赛事选手状态：已邀请、已接受、已拒绝与已超时。 */
public enum TournamentPlayerStatus implements IEnum<String> {
    INVITED,
    ACCEPTED,
    DECLINED,
    EXPIRED;

    @Override
    public String getValue() {
        return name();
    }
}
