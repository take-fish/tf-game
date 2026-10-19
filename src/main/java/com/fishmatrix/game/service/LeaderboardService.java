package com.fishmatrix.game.service;

import com.fishmatrix.game.model.LeaderboardEntry;
import com.fishmatrix.game.model.ScoreRequest;
import com.fishmatrix.game.model.ScoreResponse;
import com.fishmatrix.game.store.LeaderboardStore;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class LeaderboardService {

    private static final Set<String> GAMES = Set.of("qps-breakout");
    private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final long MAX_SCORE = 1_000_000L;

    private final LeaderboardStore store;

    public LeaderboardService(LeaderboardStore store) {
        this.store = store;
    }

    public List<LeaderboardEntry> leaderboard(String gameId, int limit) {
        requireGame(gameId);
        int safeLimit = Math.min(50, Math.max(1, limit));
        return store.top(gameId, safeLimit);
    }

    public ScoreResponse submit(ScoreRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        requireGame(request.gameId());
        requireUserId(request.userId());
        String nickname = request.nickname() == null ? "" : request.nickname().strip();
        if (nickname.isEmpty() || nickname.codePointCount(0, nickname.length()) > 32) {
            throw new IllegalArgumentException("昵称不合法");
        }
        if (request.score() < 0 || request.score() > MAX_SCORE) {
            throw new IllegalArgumentException("分数超出范围");
        }
        long best = store.submit(request.gameId(), request.userId(), nickname, request.score());
        long rank = store.rank(request.gameId(), request.userId());
        return new ScoreResponse(best, rank);
    }

    private void requireGame(String gameId) {
        if (gameId == null || !GAMES.contains(gameId)) {
            throw new IllegalArgumentException("游戏不存在");
        }
    }

    private void requireUserId(String userId) {
        if (userId == null || !USER_ID.matcher(userId).matches()) {
            throw new IllegalArgumentException("userId 格式不合法");
        }
    }
}
