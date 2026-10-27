package com.fishmatrix.game.model;

import java.util.List;
import java.util.Map;

/**
 * 小局视图。五子棋返回 moves，盲棋额外返回公开棋盘与先后手；
 * 实时竞技场返回 state 与 seq，供前端断线重连直接恢复。
 */
public record RoundView(
        String id,
        String gameId,
        int gameNo,
        String status,
        String blackUserId,
        String whiteUserId,
        String attackerUserId,
        String turnUserId,
        String winnerUserId,
        int moveCount,
        long lastMoveAt,
        Long restUntil,
        List<String> readyUserIds,
        long startedAt,
        Long endsAt,
        Long finishedAt,
        int seq,
        Map<String, Object> state,
        List<MoveView> moves,
        List<BlindPieceView> blindBoard,
        String firstUserId,
        String secondUserId) {
}
