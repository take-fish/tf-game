package com.fishmatrix.game.drill;

/**
 * 锦标赛排位赛 / 淘汰赛与赛事中台之间的解耦事件。
 *
 * <p>演练房间是进程内状态，赛事记录在数据库；用事件把「排位结束自动开淘汰赛」
 * 和「淘汰赛结束回写冠军」接起来，避免房间服务反向依赖赛事服务。</p>
 */
public final class DrillTournamentEvents {

    private DrillTournamentEvents() {
    }

    /** 15 轮排位赛结束：由赛事服务自动开启八强淘汰赛。 */
    public record PlacementFinished(String tournamentId, String roomId) {
    }

    /** 淘汰赛决赛开始：写入全站 FINAL_START 通知。 */
    public record FinalStart(
            String tournamentId, String roomId, String matchId,
            String playerA, String playerB) {
    }

    /** 淘汰赛结束：回写赛事冠亚军并发出冠军通知。 */
    public record BracketFinished(
            String tournamentId, String roomId, String bracketId,
            String championId, String championNickname,
            String runnerUpId, String runnerUpNickname) {
    }
}
