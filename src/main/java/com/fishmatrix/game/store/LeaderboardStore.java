package com.fishmatrix.game.store;

import com.fishmatrix.game.model.LeaderboardEntry;
import java.util.List;

public interface LeaderboardStore {

    long submit(String gameId, String userId, String nickname, long score);

    List<LeaderboardEntry> top(String gameId, int limit);

    long best(String gameId, String userId);

    long rank(String gameId, String userId);
}
