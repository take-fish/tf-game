package com.fishmatrix.game.arena;

/**
 * 客户端提交的竞技场动作。服务端只接受意图，所有数值由引擎计算。
 */
public record ArenaAction(
        String type,
        String target,
        String skill,
        Integer cell,
        String actionId) {
}
