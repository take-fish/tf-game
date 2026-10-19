package com.fishmatrix.game.service;

import com.fishmatrix.game.model.LeaderboardEntry;
import com.fishmatrix.game.store.LeaderboardStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class FakeLeaderboardStore implements LeaderboardStore {

    final Map<String, Map<String, Long>> scores = new HashMap<>();
    final Map<String, Map<String, String>> names = new HashMap<>();

    @Override
    public long submit(String gameId, String userId, String nickname, long score) {
        Map<String, Long> game = scores.computeIfAbsent(gameId, key -> new HashMap<>());
        long best = Math.max(game.getOrDefault(userId, 0L), score);
        game.put(userId, best);
        names.computeIfAbsent(gameId, key -> new HashMap<>()).put(userId, nickname);
        return best;
    }

    @Override
    public List<LeaderboardEntry> top(String gameId, int limit) {
        Map<String, Long> game = scores.getOrDefault(gameId, Map.of());
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(game.entrySet());
        sorted.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()));
        List<LeaderboardEntry> entries = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<String, Long> entry : sorted) {
            if (entries.size() >= limit) break;
            String nickname = names.getOrDefault(gameId, Map.of())
                    .getOrDefault(entry.getKey(), "anonymous");
            entries.add(new LeaderboardEntry(rank++, entry.getKey(), nickname, entry.getValue()));
        }
        return entries;
    }

    @Override
    public long best(String gameId, String userId) {
        return scores.getOrDefault(gameId, Map.of()).getOrDefault(userId, 0L);
    }

    @Override
    public long rank(String gameId, String userId) {
        return top(gameId, Integer.MAX_VALUE).stream()
                .filter(entry -> entry.userId().equals(userId))
                .map(LeaderboardEntry::rank)
                .findFirst()
                .orElse(0L);
    }
}
