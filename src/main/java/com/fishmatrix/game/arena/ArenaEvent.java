package com.fishmatrix.game.arena;

/**
 * 竞技场事件：只用于前端事件流展示，不参与胜负判定。
 */
public class ArenaEvent {

    public long at;
    public String type;
    public String userId;
    public String text;

    public ArenaEvent() {
    }

    public ArenaEvent(long at, String type, String userId, String text) {
        this.at = at;
        this.type = type;
        this.userId = userId;
        this.text = text;
    }
}
