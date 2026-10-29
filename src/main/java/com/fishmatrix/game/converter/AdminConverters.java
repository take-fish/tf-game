package com.fishmatrix.game.converter;

import com.fishmatrix.game.entity.GameMatchEntity;
import com.fishmatrix.game.entity.GamePlayerGameStatEntity;
import com.fishmatrix.game.entity.GamePlayerStatEntity;
import com.fishmatrix.game.entity.GameTournamentEntity;
import com.fishmatrix.game.model.AdminLeaderboardEntry;
import com.fishmatrix.game.model.MatchRecordPreview;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理台视图的转换：把实体行拼成带可见性/排序/审计信息的管理视图。
 *
 * <p>公开接口的视图仍由 {@link PlayerStatConverters} 与 {@code MatchConverters} 产出，
 * 两类视图字段口径一致，只有管理字段是新增的。</p>
 */
public final class AdminConverters {

    private AdminConverters() {
    }

    /** 综合大榜的管理视图。 */
    public static AdminLeaderboardEntry toAdminEntry(GamePlayerStatEntity entity, long rank) {
        return new AdminLeaderboardEntry(
                rank,
                entity.getUserId(),
                entity.getNickname(),
                null,
                intValue(entity.getMatches()),
                intValue(entity.getWins()),
                intValue(entity.getLosses()),
                intValue(entity.getPoints()),
                intValue(entity.getChampionships()),
                intValue(entity.getRunnerUps()),
                valueOrZero(entity.getBestScore()),
                visibleValue(entity.getVisible()),
                intValue(entity.getDisplayOrder()),
                entity.getManagedBy(),
                SqlTime.toNullableMillis(entity.getManagedAt()));
    }

    public static List<AdminLeaderboardEntry> adminEntries(List<GamePlayerStatEntity> entities) {
        List<AdminLeaderboardEntry> entries = new ArrayList<>(entities.size());
        for (int index = 0; index < entities.size(); index += 1) {
            entries.add(toAdminEntry(entities.get(index), index + 1L));
        }
        return entries;
    }

    /** 分游戏榜的管理视图。 */
    public static AdminLeaderboardEntry toAdminGameEntry(GamePlayerGameStatEntity entity,
            long rank) {
        return new AdminLeaderboardEntry(
                rank,
                entity.getUserId(),
                entity.getNickname(),
                entity.getGameId(),
                intValue(entity.getMatches()),
                intValue(entity.getWins()),
                intValue(entity.getLosses()),
                intValue(entity.getPoints()),
                intValue(entity.getChampionships()),
                0,
                0L,
                visibleValue(entity.getVisible()),
                intValue(entity.getDisplayOrder()),
                entity.getManagedBy(),
                SqlTime.toNullableMillis(entity.getManagedAt()));
    }

    public static List<AdminLeaderboardEntry> adminGameEntries(
            List<GamePlayerGameStatEntity> entities) {
        List<AdminLeaderboardEntry> entries = new ArrayList<>(entities.size());
        for (int index = 0; index < entities.size(); index += 1) {
            entries.add(toAdminGameEntry(entities.get(index), index + 1L));
        }
        return entries;
    }

    /** 比赛记录预览：赛事信息由调用方批量查好后传入，避免逐行回查。 */
    public static MatchRecordPreview toPreview(GameMatchEntity match, GameTournamentEntity tournament) {
        String gameId = match.getFirstGameId() != null
                ? match.getFirstGameId()
                : (tournament == null ? null : tournament.getGameId());
        return new MatchRecordPreview(
                match.getId(),
                match.getTournamentId(),
                tournament == null ? null : tournament.getName(),
                gameId,
                tournament == null || tournament.getKind() == null
                        ? null : tournament.getKind().name(),
                intValue(match.getRoundNo()),
                intValue(match.getSlotNo()),
                nameOf(match.getStage()),
                nameOf(match.getStatus()),
                match.getPlayer1UserId(),
                match.getPlayer1Nickname(),
                match.getPlayer2UserId(),
                match.getPlayer2Nickname(),
                intValue(match.getPlayer1Score()),
                intValue(match.getPlayer2Score()),
                match.getWinnerUserId(),
                SqlTime.toMillis(match.getCreatedAt()),
                SqlTime.toNullableMillis(match.getFinishedAt()),
                visibleValue(match.getVisible()),
                intValue(match.getDisplayOrder()));
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static boolean visibleValue(Boolean value) {
        return value == null || value;
    }

    private static int intValue(Integer value) {
        return value == null ? 0 : value;
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
