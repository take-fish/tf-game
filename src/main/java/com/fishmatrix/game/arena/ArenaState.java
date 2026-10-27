package com.fishmatrix.game.arena;

import java.util.ArrayList;
import java.util.List;

/**
 * 实时竞技场权威状态基类。所有字段都会被序列化进 state_json，
 * 前端重连时直接用它恢复整局画面。
 */
public abstract class ArenaState {

    public String gameId;
    public List<String> players = new ArrayList<>();
    public long startedAt;
    public long endsAt;
    public long finishedAt;
    public long serverTime;
    public long seed;
    public int seq;
    public boolean finished;
    public String winnerUserId;
    public String finishReason;
    public List<ArenaEvent> events = new ArrayList<>();

    protected void addEvent(long now, String type, String userId, String text) {
        events.add(new ArenaEvent(now, type, userId, text));
        while (events.size() > 40) {
            events.remove(0);
        }
    }

    protected void finish(long now, String winnerUserId, String reason) {
        this.finished = true;
        this.finishedAt = now;
        this.serverTime = now;
        this.winnerUserId = winnerUserId;
        this.finishReason = reason;
    }
}
