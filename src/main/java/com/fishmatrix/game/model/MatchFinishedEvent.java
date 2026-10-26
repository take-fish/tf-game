package com.fishmatrix.game.model;

/**
 * 一场 BO 比赛结束。赛事服务监听后负责晋级、积分结算与冠军通报。
 */
public record MatchFinishedEvent(String matchId) {
}
