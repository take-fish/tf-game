package com.fishmatrix.game.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.game.arena.ArenaGames;
import com.fishmatrix.game.converter.MatchConverters;
import com.fishmatrix.game.entity.GameRoundEntity;
import com.fishmatrix.game.mapper.MatchMapper;
import com.fishmatrix.game.model.DrillMatchReportedEvent;
import com.fishmatrix.game.model.DrillReportRequest;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.RoundRecord;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 容量编排（良跑跑）的锦标赛小局：服务端下发 seed 与截止时间，
 * 双方各自在本地跑一轮 8 人同局，上报终局战绩后按
 * 「战绩分 → SLI → 存活数」判定本局胜负，再交回 BO 结算。
 *
 * <p>状态复用 {@code game_round.state_json}：{@code seed / gameNo / deadlineAt / reports}。
 * 上报在事务内先锁定小局行，双方同时提交也不会互相覆盖。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchDrillService {

    /** 一局最多 10 分钟；到点按已上报情况收口，避免比赛永久阻塞。 */
    public static final int DRILL_SECONDS = 600;

    private final MatchMapper matches;
    private final MatchSettlementService settlement;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;

    public record StartState(String stateJson, long endsAt) {
    }

    record Report(int wins, int losses, int draws, int sli, int survivors) {
        int score() {
            return wins * 3 + draws;
        }
    }

    record State(int seed, int gameNo, long deadlineAt, Map<String, Report> reports) {
    }

    /** 小局开局：生成服务端 seed 与截止时间。 */
    public StartState start(long now, int gameNo) {
        int seed = ThreadLocalRandom.current().nextInt();
        long deadlineAt = now + DRILL_SECONDS * 1000L;
        State state = new State(seed, gameNo, deadlineAt, Map.of());
        return new StartState(write(state), deadlineAt);
    }

    /**
     * 小局视图：只暴露 seed、截止时间与已提交名单，不泄露对手战绩。
     * 静态方法供视图组装直接调用，避免服务之间循环依赖。
     */
    public static Map<String, Object> view(RoundRecord round, ObjectMapper objectMapper) {
        if (round == null || !ArenaGames.CAPACITY_DRILL.equals(round.gameId())) {
            return null;
        }
        State state = readState(round.stateJson(), objectMapper);
        if (state == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("seed", state.seed());
        view.put("gameNo", state.gameNo());
        view.put("deadlineAt", state.deadlineAt());
        view.put("reportedUserIds", List.copyOf(state.reports().keySet()));
        return view;
    }

    /** 上报本局战绩；双方都上报后结算小局并进入 BO 流程。 */
    @Transactional
    public void report(GameIdentity user, String matchId, DrillReportRequest request) {
        MatchRecord match = MatchGuards.requireParticipant(matches, user, matchId);
        if (!"RUNNING".equals(match.status())) {
            throw GameException.conflict("GAME_MATCH_NOT_RUNNING", "比赛尚未开始");
        }
        RoundRecord latest = Optional.ofNullable(matches.selectLatestRound(matchId))
                .map(MatchConverters::toRecord)
                .orElse(null);
        if (latest == null || !"RUNNING".equals(latest.status())
                || !ArenaGames.CAPACITY_DRILL.equals(latest.gameId())) {
            throw GameException.conflict("GAME_DRILL_NOT_RUNNING", "容量编排小局未在进行");
        }
        // 锁住小局行：两份上报串行进入，后一份一定能看到前一份。
        RoundRecord round = Optional.ofNullable(matches.selectRoundForUpdate(latest.id()))
                .map(MatchConverters::toRecord)
                .orElse(null);
        if (round == null || !"RUNNING".equals(round.status())) {
            throw GameException.conflict("GAME_DRILL_NOT_RUNNING", "容量编排小局未在进行");
        }
        State state = readState(round.stateJson(), objectMapper);
        if (state == null) {
            throw GameException.conflict("GAME_DRILL_STATE_MISSING", "本局状态缺失，请重新开局");
        }
        if (state.reports().containsKey(user.userId())) {
            return;
        }
        if (state.deadlineAt() > 0 && System.currentTimeMillis() >= state.deadlineAt()) {
            // 截止后迟到的上报不再计入；结算后正常返回，避免抛异常把结算一起回滚。
            settleExpiredLocked(match, round, state);
            publisher.publishEvent(new DrillMatchReportedEvent(matchId));
            return;
        }
        if (request == null || request.gameNo() != round.gameNo()) {
            throw GameException.badRequest("GAME_DRILL_GAME_MISMATCH", "局次不一致，请等待同步");
        }
        validateReport(request);
        Map<String, Report> reports = new LinkedHashMap<>(state.reports());
        reports.put(user.userId(), new Report(
                request.wins(), request.losses(), request.draws(),
                request.sli(), request.survivors()));
        State merged = new State(state.seed(), state.gameNo(), state.deadlineAt(), reports);
        if (matches.updateArenaState(
                round.id(), write(merged), round.stateVersion() + 1, round.moveCount()) == 0) {
            throw GameException.conflict("GAME_DRILL_STATE_STALE", "小局状态已更新，请刷新后重试");
        }
        if (reports.size() >= 2) {
            String winner = compareWinner(match, reports);
            settlement.finishGame(match, round, winner,
                    winner == null ? "容量编排同分" : "容量编排占优");
            return;
        }
        publisher.publishEvent(new DrillMatchReportedEvent(matchId));
    }

    /** 定时巡检：截止时间到后按已上报情况收口。 */
    @Transactional
    public void settleExpired(RoundRecord due) {
        if (due == null || !"RUNNING".equals(due.status())
                || !ArenaGames.CAPACITY_DRILL.equals(due.gameId())
                || due.endsAt() == null
                || System.currentTimeMillis() < due.endsAt()) {
            return;
        }
        MatchRecord match = Optional.ofNullable(matches.selectById(due.matchId()))
                .map(MatchConverters::toRecord)
                .orElse(null);
        if (match == null || !"RUNNING".equals(match.status())) {
            return;
        }
        RoundRecord round = Optional.ofNullable(matches.selectRoundForUpdate(due.id()))
                .map(MatchConverters::toRecord)
                .orElse(null);
        if (round == null || !"RUNNING".equals(round.status())) {
            return;
        }
        State state = readState(round.stateJson(), objectMapper);
        if (state == null) {
            settlement.finishGame(match, round, null, "容量编排超时");
            return;
        }
        settleExpiredLocked(match, round, state);
    }

    private void settleExpiredLocked(MatchRecord match, RoundRecord round, State state) {
        Map<String, Report> reports = state.reports();
        String winner = null;
        String reason = "容量编排超时";
        if (reports.size() == 1) {
            String only = reports.keySet().iterator().next();
            if (only.equals(match.player1UserId()) || only.equals(match.player2UserId())) {
                winner = only;
                reason = "对手未提交 · 超时判负";
            }
        }
        settlement.finishGame(match, round, winner, reason);
    }

    /** 战绩分 → SLI → 存活数；完全同分返回 null，由 BO 结算按种子顺位判定。 */
    static String compareWinner(MatchRecord match, Map<String, Report> reports) {
        Report first = reports.get(match.player1UserId());
        Report second = reports.get(match.player2UserId());
        if (first == null || second == null) {
            return null;
        }
        int result = compare(first, second);
        if (result > 0) {
            return match.player1UserId();
        }
        return result < 0 ? match.player2UserId() : null;
    }

    static int compare(Report first, Report second) {
        int result = Integer.compare(first.score(), second.score());
        if (result == 0) {
            result = Integer.compare(first.sli(), second.sli());
        }
        if (result == 0) {
            result = Integer.compare(first.survivors(), second.survivors());
        }
        return result;
    }

    /** 一局一轮战斗：胜/负/平各记 1 场，SLI 与存活数用于同分比较。 */
    static void validateReport(DrillReportRequest request) {
        if (request.wins() < 0 || request.losses() < 0 || request.draws() < 0
                || request.sli() < 0 || request.survivors() < 0 || request.survivors() > 8
                || request.wins() + request.losses() + request.draws() != 1) {
            throw GameException.badRequest("GAME_DRILL_REPORT_INVALID", "本局战绩格式不正确");
        }
    }

    private String write(State state) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("seed", state.seed());
        json.put("gameNo", state.gameNo());
        json.put("deadlineAt", state.deadlineAt());
        json.put("reported", List.copyOf(state.reports().keySet()));
        json.put("reports", state.reports());
        try {
            return objectMapper.writeValueAsString(json);
        } catch (Exception exception) {
            throw GameException.conflict("GAME_DRILL_STATE_WRITE", "本局状态写入失败");
        }
    }

    private static State readState(String stateJson, ObjectMapper objectMapper) {
        if (stateJson == null || stateJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(stateJson);
            Map<String, Report> reports = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = root.path("reports").fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode value = entry.getValue();
                reports.put(entry.getKey(), new Report(
                        value.path("wins").asInt(),
                        value.path("losses").asInt(),
                        value.path("draws").asInt(),
                        value.path("sli").asInt(),
                        value.path("survivors").asInt()));
            }
            return new State(
                    root.path("seed").asInt(),
                    root.path("gameNo").asInt(),
                    root.path("deadlineAt").asLong(),
                    reports);
        } catch (Exception exception) {
            log.warn("容量编排小局状态解析失败", exception);
            return null;
        }
    }
}
