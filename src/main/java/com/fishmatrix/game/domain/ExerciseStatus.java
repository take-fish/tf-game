package com.fishmatrix.game.domain;

import com.baomidou.mybatisplus.annotation.IEnum;

/** 巡检房间状态：等待准备、进行中、已结束与已取消。 */
public enum ExerciseStatus implements IEnum<String> {
    WAITING,
    RUNNING,
    FINISHED,
    CANCELLED;

    @Override
    public String getValue() {
        return name();
    }
}
