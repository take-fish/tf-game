package com.fishmatrix.game.arena;

/**
 * 集群巡检对抗的客户端输入意图：移动向量、瞄准角度、开火与技能。
 * 服务端按 25Hz 积分，所有碰撞、命中与得分均由服务端计算。
 */
public record ExerciseInput(
        Integer inputSeq,
        Double moveX,
        Double moveY,
        Double aim,
        Boolean fire,
        String skill,
        Double skillX,
        Double skillY) {

    public static ExerciseInput empty() {
        return new ExerciseInput(null, 0.0, 0.0, 0.0, false, null, null, null);
    }
}
