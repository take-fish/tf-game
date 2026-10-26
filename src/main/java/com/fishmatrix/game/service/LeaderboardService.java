package com.fishmatrix.game.service;

import com.fishmatrix.common.auth.CurrentUser;
import com.fishmatrix.game.model.LeaderboardEntry;
import com.fishmatrix.game.model.ScoreRequest;
import com.fishmatrix.game.model.ScoreResponse;
import com.fishmatrix.game.store.PlayerStatRepository;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 综合排行榜：五子棋与打砖块共用 game_player_stat。
 * 排行榜接口不再区分游戏，gameId 仅为兼容旧前端保留。
 */
@Service
public class LeaderboardService {

    private static final Set<String> SCORE_GAMES = Set.of("qps-breakout");
    private static final long MAX_SCORE = 1_000_000L;

    private final PlayerStatRepository stats;

    public LeaderboardService(PlayerStatRepository stats) {
        this.stats = stats;
    }

    public List<LeaderboardEntry> leaderboard(String gameId, int limit) {
        return stats.top(limit);
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
        stats.recordBreakoutScore(user.userId(), nickname, request.score());
        return new ScoreResponse(stats.bestScore(user.userId()), stats.rank(user.userId()));
    }
}
