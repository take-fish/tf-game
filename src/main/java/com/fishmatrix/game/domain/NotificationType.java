package com.fishmatrix.game.domain;

import com.baomidou.mybatisplus.annotation.IEnum;

/** 全站通知类型：决赛开始与冠军诞生。 */
public enum NotificationType implements IEnum<String> {
    FINAL_START,
    CHAMPION;

    @Override
    public String getValue() {
        return name();
    }
}
