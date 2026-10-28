package com.fishmatrix.game.controller;

import com.fishmatrix.common.auth.CurrentUser;
import com.fishmatrix.common.auth.RequirePermission;
import com.fishmatrix.common.auth.UserContext;
import com.fishmatrix.game.auth.GamePermissions;
import com.fishmatrix.game.model.AdminLeaderboardEntry;
import com.fishmatrix.game.model.DisplayOrderRequest;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.LeaderboardEditRequest;
import com.fishmatrix.game.model.MatchRecordPreview;
import com.fishmatrix.game.model.RecordManageRequest;
import com.fishmatrix.game.service.GameAdminService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理台接口：排行榜治理（改统计数值、改展示顺序）与比赛记录治理（是否展示、展示顺序）。
 *
 * <p>权限码见 {@link GamePermissions}；{@code @RequirePermission} 是入口拦截，
 * Service 内还会再校验一次，防止绕过 Web 层直接调用。</p>
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/game/admin")
public class GameAdminController {

    private final GameAdminService admin;

    @RequirePermission(GamePermissions.LEADERBOARD_MANAGE)
    @GetMapping("/leaderboards")
    public Map<String, Object> leaderboards(@RequestParam(required = false) String gameId,
            @RequestParam(defaultValue = "100") int limit) {
        List<AdminLeaderboardEntry> entries = admin.leaderboard(identity(), gameId, limit);
        return Map.of("gameId", gameId == null ? "" : gameId, "entries", entries);
    }

    @RequirePermission(GamePermissions.LEADERBOARD_MANAGE)
    @PatchMapping("/leaderboards/{userId}")
    public AdminLeaderboardEntry updateLeaderboard(@PathVariable String userId,
            @RequestParam(required = false) String gameId,
            @RequestBody LeaderboardEditRequest request) {
        return admin.updateEntry(identity(), gameId, userId, request);
    }

    @RequirePermission(GamePermissions.LEADERBOARD_MANAGE)
    @PostMapping("/leaderboards/order")
    public Map<String, Object> orderLeaderboards(@RequestParam(required = false) String gameId,
            @RequestBody DisplayOrderRequest request) {
        List<AdminLeaderboardEntry> entries = admin.reorderLeaderboard(identity(), gameId, request);
        return Map.of("gameId", gameId == null ? "" : gameId, "entries", entries);
    }

    @RequirePermission(GamePermissions.RECORD_MANAGE)
    @GetMapping("/records")
    public Map<String, Object> records(@RequestParam(defaultValue = "20") int limit) {
        List<MatchRecordPreview> records = admin.records(identity(), limit);
        return Map.of("records", records);
    }

    @RequirePermission(GamePermissions.RECORD_MANAGE)
    @PatchMapping("/records/{matchId}")
    public MatchRecordPreview updateRecord(@PathVariable String matchId,
            @RequestBody RecordManageRequest request) {
        return admin.updateRecord(identity(), matchId, request);
    }

    @RequirePermission(GamePermissions.RECORD_MANAGE)
    @PostMapping("/records/order")
    public Map<String, Object> orderRecords(@RequestBody DisplayOrderRequest request) {
        List<MatchRecordPreview> records = admin.reorderRecords(identity(), request);
        return Map.of("records", records);
    }

    private GameIdentity identity() {
        CurrentUser user = UserContext.require();
        return GameIdentity.of(user);
    }
}
