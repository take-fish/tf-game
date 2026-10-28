package com.fishmatrix.game.model;

/**
 * 管理员调整比赛记录：字段为 null 表示不改。
 * 隐藏只影响记录列表，不影响对局本身与详情接口。
 */
public record RecordManageRequest(Boolean visible, Integer displayOrder) {
}
