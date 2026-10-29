package com.fishmatrix.game.domain;

import com.baomidou.mybatisplus.annotation.IEnum;

/** 聊天频道：公开房间、队伍频道与选手私聊。 */
public enum ChatChannel implements IEnum<String> {
    PUBLIC,
    TEAM,
    PLAYER_DM;

    @Override
    public String getValue() {
        return name();
    }
}
