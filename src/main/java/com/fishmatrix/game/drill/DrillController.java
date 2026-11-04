package com.fishmatrix.game.drill;

import com.fishmatrix.common.auth.UserContext;
import com.fishmatrix.game.model.GameIdentity;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 容量编排演练的锁步房间入口：创建 / 加入 / 准备 / 轮末报到 / 查询状态。
 *
 * <p>客户端按 {@code seed} 本地跑引擎，轮末提交血量与选秀结果；
 * 名次与淘汰由服务端统一裁决，因此所有人在同一局里看到同一套结果。</p>
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/game/drill")
public class DrillController {

    private final DrillRoomService rooms;
    private final DrillKnockoutService knockout;

    public record CreateRoomRequest(String source, String mode) {
    }

    public record ChatRequest(String text) {
    }

    public record ReportRequest(Integer round, Integer hp, String result, String carouselId) {
    }

    public record KnockoutReportRequest(
            Integer game, Integer wins, Integer losses, Integer draws, Integer sli, Integer survivors) {
    }

    @PostMapping("/rooms")
    public Map<String, Object> create(@RequestBody(required = false) CreateRoomRequest request) {
        return rooms.create(identity(), request == null ? null : request.source(), request == null ? null : request.mode());
    }

    @GetMapping("/rooms/{roomId}")
    public Map<String, Object> state(@PathVariable String roomId) {
        return rooms.state(identity(), roomId);
    }

    @PostMapping("/rooms/{roomId}/join")
    public Map<String, Object> join(@PathVariable String roomId) {
        return rooms.join(identity(), roomId);
    }

    @PostMapping("/rooms/{roomId}/ready")
    public Map<String, Object> ready(@PathVariable String roomId) {
        return rooms.ready(identity(), roomId);
    }

    @PostMapping("/rooms/{roomId}/skip")
    public Map<String, Object> skip(@PathVariable String roomId) {
        return rooms.requestSkip(identity(), roomId);
    }

    @PostMapping("/rooms/{roomId}/chat")
    public Map<String, Object> chat(@PathVariable String roomId, @RequestBody ChatRequest request) {
        return rooms.chat(identity(), roomId, request == null ? null : request.text());
    }

    @PostMapping("/rooms/{roomId}/report")
    public Map<String, Object> report(@PathVariable String roomId, @RequestBody ReportRequest request) {
        return rooms.report(
                identity(),
                roomId,
                request == null || request.round() == null ? -1 : request.round(),
                request == null || request.hp() == null ? 0 : request.hp(),
                request == null ? null : request.result(),
                request == null ? null : request.carouselId());
    }

    /** 排位赛结束后开淘汰赛（八强 BO3 / 四强 BO5 / 决赛 BO5）。 */
    @PostMapping("/rooms/{roomId}/knockout")
    public Map<String, Object> startKnockout(@PathVariable String roomId) {
        return knockout.start(identity(), roomId);
    }

    @GetMapping("/rooms/{roomId}/knockout")
    public Map<String, Object> latestKnockout(@PathVariable String roomId) {
        return knockout.latest(identity(), roomId);
    }

    @GetMapping("/knockout/{bracketId}")
    public Map<String, Object> knockoutState(@PathVariable String bracketId) {
        return knockout.state(identity(), bracketId);
    }

    @PostMapping("/knockout/{bracketId}/matches/{matchId}/report")
    public Map<String, Object> knockoutReport(
            @PathVariable String bracketId,
            @PathVariable String matchId,
            @RequestBody KnockoutReportRequest request) {
        return knockout.report(
                identity(),
                bracketId,
                matchId,
                request == null
                        ? null
                        : new DrillKnockoutService.GameReport(
                                request.game() == null ? -1 : request.game(),
                                request.wins() == null ? 0 : request.wins(),
                                request.losses() == null ? 0 : request.losses(),
                                request.draws() == null ? 0 : request.draws(),
                                request.sli() == null ? 0 : request.sli(),
                                request.survivors() == null ? 0 : request.survivors()));
    }

    private static GameIdentity identity() {
        return GameIdentity.of(UserContext.require());
    }
}
