package com.fishmatrix.game.model;

/**
 * 容量编排小局上报：一局一轮战斗，双方各自提交本局终局战绩。
 */
public record DrillReportRequest(
        int gameNo,
        int wins,
        int losses,
        int draws,
        int sli,
        int survivors) {
}
