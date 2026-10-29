package com.fishmatrix.game.converter;

import com.fishmatrix.game.entity.GamePlayerGameStatEntity;
import com.fishmatrix.game.entity.GamePlayerStatEntity;
import com.fishmatrix.game.model.LeaderboardEntry;
import java.util.ArrayList;
import java.util.List;

/**
 * 排行榜行与 {@link LeaderboardEntry} 的转换。
 *
 * <p>名次由查询结果顺序决定（1-based），与迁移前 {@code RowMapper} 的 rowNum + 1 完全一致。</p>
 */
public final class PlayerStatConverters {

    private PlayerStatConverters() {
    }

    /** 综合大榜：名次 + 冠亚军/积分。 */
    public static LeaderboardEntry toEntry(GamePlayerStatEntity entity, long rank) {
        return new LeaderboardEntry(
                rank,
                entity.getUserId(),
                entity.getNickname(),
                intValue(entity.getChampionships()),
                intValue(entity.getRunnerUps()),
                intValue(entity.getPoints()));
    }

    public static List<LeaderboardEntry> entries(List<GamePlayerStatEntity> entities) {
        List<LeaderboardEntry> entries = new ArrayList<>(entities.size());
        for (int index = 0; index < entities.size(); index += 1) {
            entries.add(toEntry(entities.get(index), index + 1L));
        }
        return entries;
    }

    /** 分游戏榜：场次/胜负与积分，冠军次数单独展示。 */
    public static LeaderboardEntry toGameEntry(GamePlayerGameStatEntity entity, long rank) {
        return new LeaderboardEntry(
                rank,
                entity.getUserId(),
                entity.getNickname(),
                intValue(entity.getPoints()),
                intValue(entity.getPoints()),
                intValue(entity.getMatches()),
                intValue(entity.getWins()),
                intValue(entity.getLosses()),
                intValue(entity.getChampionships()),
                0L);
    }

    public static List<LeaderboardEntry> gameEntries(List<GamePlayerGameStatEntity> entities) {
        List<LeaderboardEntry> entries = new ArrayList<>(entities.size());
        for (int index = 0; index < entities.size(); index += 1) {
            entries.add(toGameEntry(entities.get(index), index + 1L));
        }
        return entries;
    }

    /** 空值按迁移前口径返回 0。 */
    public static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private static int intValue(Integer value) {
        return value == null ? 0 : value;
    }
}
