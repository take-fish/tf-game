package com.fishmatrix.game.domain;

import com.baomidou.mybatisplus.annotation.IEnum;

/** 赛事状态：邀请中、进行中、已结束与已取消。 */
public enum TournamentStatus implements IEnum<String> {
    INVITING,
    RUNNING,
    FINISHED,
    CANCELLED;

    @Override
    public String getValue() {
        return name();
    }
}
