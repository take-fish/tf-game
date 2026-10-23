package com.fishmatrix.game.controller;

import com.fishmatrix.common.auth.CurrentUser;
import com.fishmatrix.common.auth.UserContext;
import com.fishmatrix.game.model.LeaderboardEntry;
import com.fishmatrix.game.model.ScoreRequest;
import com.fishmatrix.game.model.ScoreResponse;
import com.fishmatrix.game.service.LeaderboardService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/game")
public class GameController {

    private final LeaderboardService leaderboardService;

    public GameController(LeaderboardService leaderboardService) {
        this.leaderboardService = leaderboardService;
    }

    @GetMapping("/leaderboard")
    public Map<String, Object> leaderboard(
            @RequestParam(defaultValue = "qps-breakout") String gameId,
            @RequestParam(defaultValue = "10") int limit) {
        List<LeaderboardEntry> entries = leaderboardService.leaderboard(gameId, limit);
        return Map.of("gameId", gameId, "entries", entries);
    }

    @PostMapping("/scores")
    public ScoreResponse submit(@RequestBody ScoreRequest request) {
        // 身份只取服务端校验结果，忽略客户端自报的 userId / nickname。
        CurrentUser user = UserContext.require();
        return leaderboardService.submit(request, user);
    }
}
