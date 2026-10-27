package com.fishmatrix.game.store;

import com.fishmatrix.game.model.LeaderboardEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

@Repository
public class RedisLeaderboardStore implements LeaderboardStore {

    private static final String PREFIX = "tf:game:leaderboard:";

    private final StringRedisTemplate redis;

    public RedisLeaderboardStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long submit(String gameId, String userId, String nickname, long score) {
        ZSetOperations<String, String> zset = redis.opsForZSet();
        String key = key(gameId);
        Double current = zset.score(key, userId);
        if (current == null || score > current) {
            zset.add(key, userId, score);
        }
        redis.opsForHash().put(namesKey(gameId), userId, nickname);
        Double best = zset.score(key, userId);
        return best == null ? score : best.longValue();
    }

    @Override
    public List<LeaderboardEntry> top(String gameId, int limit) {
        ZSetOperations<String, String> zset = redis.opsForZSet();
        Set<ZSetOperations.TypedTuple<String>> tupleSet =
                zset.reverseRangeWithScores(key(gameId), 0, Math.max(0, limit - 1));
        if (tupleSet == null || tupleSet.isEmpty()) {
            return List.of();
        }
        List<ZSetOperations.TypedTuple<String>> tuples = new ArrayList<>(tupleSet);
        List<String> userIds = tuples.stream().map(ZSetOperations.TypedTuple::getValue).toList();
        HashOperations<String, String, String> hash = redis.opsForHash();
        List<String> nicknames = hash.multiGet(namesKey(gameId), new ArrayList<>(userIds));
        List<LeaderboardEntry> entries = new ArrayList<>();
        int rank = 1;
        for (int i = 0; i < tuples.size(); i++) {
            ZSetOperations.TypedTuple<String> tuple = tuples.get(i);
            if (tuple.getValue() == null) continue;
            String nickname = i < nicknames.size() ? nicknames.get(i) : null;
            entries.add(new LeaderboardEntry(
                    rank++,
                    tuple.getValue(),
                    nickname == null ? "anonymous" : nickname,
                    0,
                    0,
                    tuple.getScore() == null ? 0 : (int) tuple.getScore().longValue()));
        }
        return entries;
    }

    @Override
    public long best(String gameId, String userId) {
        Double score = redis.opsForZSet().score(key(gameId), userId);
        return score == null ? 0 : score.longValue();
    }

    @Override
    public long rank(String gameId, String userId) {
        Long rank = redis.opsForZSet().reverseRank(key(gameId), userId);
        return rank == null ? 0 : rank + 1;
    }

    private String key(String gameId) {
        return PREFIX + gameId;
    }

    private String namesKey(String gameId) {
        return PREFIX + gameId + ":names";
    }
}
