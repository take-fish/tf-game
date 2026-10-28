package com.fishmatrix.game.service;

import com.fishmatrix.game.auth.GamePermissions;
import com.fishmatrix.game.converter.AdminConverters;
import com.fishmatrix.game.entity.GamePlayerGameStatEntity;
import com.fishmatrix.game.entity.GamePlayerStatEntity;
import com.fishmatrix.game.mapper.MatchMapper;
import com.fishmatrix.game.mapper.PlayerStatMapper;
import com.fishmatrix.game.model.AdminLeaderboardEntry;
import com.fishmatrix.game.model.DisplayOrderRequest;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.GameIds;
import com.fishmatrix.game.model.LeaderboardEditRequest;
import com.fishmatrix.game.model.MatchRecordPreview;
import com.fishmatrix.game.model.RecordManageRequest;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 管理台的排行榜与比赛记录治理。
 *
 * <p>两类能力各自对应一个权限码：{@link GamePermissions#LEADERBOARD_MANAGE} 管排行榜
 * （改统计数值、改展示顺序），{@link GamePermissions#RECORD_MANAGE} 管比赛记录
 * （是否展示、展示顺序）。管理操作只影响展示层，不改对局本身。</p>
 */
@RequiredArgsConstructor
@Service
public class GameAdminService {

    private static final int MAX_LIMIT = 200;
    private static final int MAX_DISPLAY_ORDER = 99_999;
    private static final long MAX_SCORE = 1_000_000L;

    private final PlayerStatMapper stats;
    private final MatchMapper matches;
    private final GameRecordService records;

    /** 管理台排行榜：包含被下架的行，按展示权重与战绩排序。 */
    public List<AdminLeaderboardEntry> leaderboard(GameIdentity user, String gameId, int limit) {
        GamePermissions.require(user, GamePermissions.LEADERBOARD_MANAGE);
        return board(normalizeScope(gameId), clamp(limit));
    }

    private List<AdminLeaderboardEntry> board(String scope, int bounded) {
        if (scope == null) {
            return AdminConverters.adminEntries(stats.selectAllOverall(bounded));
        }
        return AdminConverters.adminGameEntries(stats.selectAllByGame(scope, bounded));
    }

    /** 修改一条排行榜数据（统计数值 / 可见性 / 展示顺序），返回修改后的行。 */
    public AdminLeaderboardEntry updateEntry(GameIdentity user, String gameId, String userId,
            LeaderboardEditRequest request) {
        GamePermissions.require(user, GamePermissions.LEADERBOARD_MANAGE);
        String scope = normalizeScope(gameId);
        String target = requireText(userId, "缺少选手标识");
        validateEdit(request);
        int updated = applyEdit(scope, target, request, user.userId(), LocalDateTime.now());
        if (updated == 0) {
            throw GameException.notFound("排行榜条目不存在");
        }
        return refresh(scope, target);
    }

    /** 批量调整展示顺序，返回调整后的榜单。 */
    public List<AdminLeaderboardEntry> reorderLeaderboard(GameIdentity user, String gameId,
            DisplayOrderRequest request) {
        GamePermissions.require(user, GamePermissions.LEADERBOARD_MANAGE);
        String scope = normalizeScope(gameId);
        List<DisplayOrderRequest.Item> items = requireItems(request);
        LocalDateTime now = LocalDateTime.now();
        Set<String> seen = new HashSet<>();
        for (DisplayOrderRequest.Item item : items) {
            String target = requireText(item.id(), "缺少选手标识");
            if (!seen.add(target)) {
                throw invalid("同一个选手出现了多次：" + target);
            }
            int order = requireOrder(item.displayOrder());
            int updated = applyOrder(scope, target, order, user.userId(), now);
            if (updated == 0) {
                throw GameException.notFound("排行榜条目不存在：" + target);
            }
        }
        return leaderboard(user, gameId, MAX_LIMIT);
    }

    /** 管理台比赛记录列表：包含被隐藏的记录。 */
    public List<MatchRecordPreview> records(GameIdentity user, int limit) {
        GamePermissions.require(user, GamePermissions.RECORD_MANAGE);
        return records.listForAdmin(limit);
    }

    /** 调整一条比赛记录的展示状态，返回调整后的行。 */
    public MatchRecordPreview updateRecord(GameIdentity user, String matchId,
            RecordManageRequest request) {
        GamePermissions.require(user, GamePermissions.RECORD_MANAGE);
        String target = requireText(matchId, "缺少比赛标识");
        if (request == null || (request.visible() == null && request.displayOrder() == null)) {
            throw invalid("没有需要修改的字段");
        }
        Integer order = request.displayOrder() == null ? null : requireOrder(request.displayOrder());
        int updated = matches.updateRecordManaged(target, request.visible(), order,
                user.userId(), LocalDateTime.now());
        if (updated == 0) {
            throw GameException.notFound("比赛记录不存在");
        }
        return refreshRecord(target);
    }

    /** 批量调整比赛记录展示顺序，返回调整后的记录列表。 */
    public List<MatchRecordPreview> reorderRecords(GameIdentity user, DisplayOrderRequest request) {
        GamePermissions.require(user, GamePermissions.RECORD_MANAGE);
        List<DisplayOrderRequest.Item> items = requireItems(request);
        LocalDateTime now = LocalDateTime.now();
        Set<String> seen = new HashSet<>();
        for (DisplayOrderRequest.Item item : items) {
            String target = requireText(item.id(), "缺少比赛标识");
            if (!seen.add(target)) {
                throw invalid("同一场比赛出现了多次：" + target);
            }
            int updated = matches.updateRecordManaged(target, null, requireOrder(item.displayOrder()),
                    user.userId(), now);
            if (updated == 0) {
                throw GameException.notFound("比赛记录不存在：" + target);
            }
        }
        return records.listForAdmin(Integer.MAX_VALUE);
    }

    private int applyEdit(String scope, String userId, LeaderboardEditRequest request,
            String managedBy, LocalDateTime now) {
        if (scope == null) {
            return stats.updateManaged(userId, request.matches(), request.wins(), request.losses(),
                    request.points(), request.championships(), request.runnerUps(),
                    request.bestScore(), request.visible(), request.displayOrder(), managedBy, now);
        }
        return stats.updateManagedGame(scope, userId, request.matches(), request.wins(),
                request.losses(), request.points(), request.championships(), request.visible(),
                request.displayOrder(), managedBy, now);
    }

    private int applyOrder(String scope, String userId, int order, String managedBy,
            LocalDateTime now) {
        if (scope == null) {
            return stats.updateManaged(userId, null, null, null, null, null, null, null, null,
                    order, managedBy, now);
        }
        return stats.updateManagedGame(scope, userId, null, null, null, null, null, null, order,
                managedBy, now);
    }

    /** 修改后回读一行；榜单里找不到（超出上限）时按 0 名次返回。 */
    private AdminLeaderboardEntry refresh(String scope, String userId) {
        for (AdminLeaderboardEntry entry : board(scope, MAX_LIMIT)) {
            if (entry.userId().equals(userId)) {
                return entry;
            }
        }
        if (scope == null) {
            GamePlayerStatEntity row = stats.selectById(userId);
            return row == null ? null : AdminConverters.toAdminEntry(row, 0L);
        }
        GamePlayerGameStatEntity row = stats.selectGameStat(scope, userId);
        return row == null ? null : AdminConverters.toAdminGameEntry(row, 0L);
    }

    private MatchRecordPreview refreshRecord(String matchId) {
        for (MatchRecordPreview preview : records.listForAdmin(Integer.MAX_VALUE)) {
            if (preview.matchId().equals(matchId)) {
                return preview;
            }
        }
        throw GameException.notFound("比赛记录不存在");
    }

    private void validateEdit(LeaderboardEditRequest request) {
        if (request == null) {
            throw invalid("没有需要修改的字段");
        }
        boolean hasValue = request.matches() != null || request.wins() != null
                || request.losses() != null || request.points() != null
                || request.championships() != null || request.runnerUps() != null
                || request.bestScore() != null || request.visible() != null
                || request.displayOrder() != null;
        if (!hasValue) {
            throw invalid("没有需要修改的字段");
        }
        requireNonNegative("场次", request.matches());
        requireNonNegative("胜场", request.wins());
        requireNonNegative("负场", request.losses());
        requireNonNegative("积分", request.points());
        requireNonNegative("冠军次数", request.championships());
        requireNonNegative("亚军次数", request.runnerUps());
        if (request.bestScore() != null && (request.bestScore() < 0 || request.bestScore() > MAX_SCORE)) {
            throw invalid("最高分必须在 0 到 " + MAX_SCORE + " 之间");
        }
        if (request.displayOrder() != null) {
            requireOrder(request.displayOrder());
        }
    }

    private String normalizeScope(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            return null;
        }
        String scope = gameId.trim();
        if (!GameIds.ALL.contains(scope)) {
            throw GameException.badRequest("GAME_UNKNOWN_GAME", "未知的游戏类型：" + scope);
        }
        return scope;
    }

    private int clamp(int limit) {
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private int requireOrder(Integer displayOrder) {
        if (displayOrder == null) {
            throw invalid("缺少展示顺序");
        }
        if (displayOrder < 0 || displayOrder > MAX_DISPLAY_ORDER) {
            throw invalid("展示顺序必须在 0 到 " + MAX_DISPLAY_ORDER + " 之间");
        }
        return displayOrder;
    }

    private void requireNonNegative(String label, Integer value) {
        if (value != null && value < 0) {
            throw invalid(label + "不能为负数");
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw invalid(message);
        }
        return value.trim();
    }

    private List<DisplayOrderRequest.Item> requireItems(DisplayOrderRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw invalid("缺少需要排序的条目");
        }
        return request.items();
    }

    private GameException invalid(String message) {
        return GameException.badRequest("GAME_INVALID_ARGUMENT", message);
    }
}
