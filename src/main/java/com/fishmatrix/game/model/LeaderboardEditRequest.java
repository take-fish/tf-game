package com.fishmatrix.game.model;

/**
 * 管理员调整排行榜条目：字段为 null 表示不改，便于前端只提交改动的列。
 *
 * <p>数值必须是增量结算之外的人工修正（例如误判、数据修正），
 * 每次调整都会写入 managed_by / managed_at。</p>
 */
public record LeaderboardEditRequest(
        Integer matches,
        Integer wins,
        Integer losses,
        Integer points,
        Integer championships,
        Integer runnerUps,
        Long bestScore,
        Boolean visible,
        Integer displayOrder) {
}
