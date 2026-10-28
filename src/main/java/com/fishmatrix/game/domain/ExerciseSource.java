package com.fishmatrix.game.domain;

import com.baomidou.mybatisplus.annotation.IEnum;

/** 巡检房间来源：休闲大厅创建或赛事小局自动开局。 */
public enum ExerciseSource implements IEnum<String> {
    CASUAL,
    TOURNAMENT;

    @Override
    public String getValue() {
        return name();
    }
}
