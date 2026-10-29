package com.fishmatrix.game.service;

import com.fishmatrix.common.auth.CurrentUser;
import com.fishmatrix.game.converter.PlayerStatConverters;
import com.fishmatrix.game.mapper.PlayerStatMapper;
import com.fishmatrix.game.model.GameIds;
import com.fishmatrix.game.model.LeaderboardEntry;
import com.fishmatrix.game.model.ScoreRequest;
import com.fishmatrix.game.model.ScoreResponse;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 排行榜：overall / qps-breakout 等返回综合榜；
 * 五子棋、盲棋按 gameId 返回各自的棋类榜。
 */
@RequiredArgsConstructor
@Service
public class LeaderboardService {

    private static final Set<String> SCORE_GAMES = Set.of("qps-breakout");
    private static final Set<String> GAME_LEADERBOARDS =
            Set.of(GameIds.HEATMAP_GOMOKU, GameIds.BLIND_XIANGQI);
    private static final long MAX_SCORE = 1_000_000L;

    private final PlayerStatMapper stats;

    public List<LeaderboardEntry> leaderboard(String gameId, int limit) {
        int safeLimit = Math.min(50, Math.max(1, limit));
        if (gameId != null && GAME_LEADERBOARDS.contains(gameId)) {
            return PlayerStatConverters.gameEntries(stats.selectTopByGame(gameId, safeLimit));
        }
        return PlayerStatConverters.entries(stats.selectTop(safeLimit));
    }

    public ScoreResponse submit(ScoreRequest request, CurrentUser user) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (request.gameId() == null || !SCORE_GAMES.contains(request.gameId())) {
            throw new IllegalArgumentException("该游戏不支持提交分数");
        }
        if (request.score() < 0 || request.score() > MAX_SCORE) {
            throw new IllegalArgumentException("分数超出范围");
        }
        String nickname = user.nickname() == null ? "" : user.nickname().strip();
        if (nickname.isEmpty() || nickname.codePointCount(0, nickname.length()) > 32) {
            throw new IllegalArgumentException("昵称不合法");
        }
        stats.raiseBestScore(user.userId(), nickname, request.score());
        return new ScoreResponse(
                PlayerStatConverters.valueOrZero(stats.selectBestScore(user.userId())),
                PlayerStatConverters.valueOrZero(stats.selectRank(user.userId())));
    }
}
