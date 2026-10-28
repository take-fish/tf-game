package com.fishmatrix.game.domain;

import com.baomidou.mybatisplus.annotation.IEnum;

/** 巡检房间模式：自由混战、两队对抗与单人训练。 */
public enum ExerciseMode implements IEnum<String> {
    FFA,
    TEAM,
    TRAINING;

    @Override
    public String getValue() {
        return name();
    }
}
