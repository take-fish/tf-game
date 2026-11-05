package com.fishmatrix.game.model;

/**
 * 容量编排小局收到一份新上报，通知比赛通道刷新「已提交」状态。
 */
public record DrillMatchReportedEvent(String matchId) {
}
