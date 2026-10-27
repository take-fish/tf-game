package com.fishmatrix.game.arena;

/**
 * 实时竞技场引擎：纯服务端状态机。实现必须只修改传入的 state，
 * 不访问数据库/网络，便于单测和按固定步长复现。
 */
public interface ArenaEngine<S extends ArenaState> {

    String gameId();

    Class<S> stateType();

    long roundMillis();

    S create(ArenaContext context);

    void apply(S state, ArenaAction action, String userId, long now);

    void tick(S state, long now);

    boolean finished(S state);

    String winnerUserId(S state);

    String finishReason(S state);
}
