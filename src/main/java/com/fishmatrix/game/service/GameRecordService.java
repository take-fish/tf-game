package com.fishmatrix.game.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fishmatrix.game.converter.AdminConverters;
import com.fishmatrix.game.entity.GameMatchEntity;
import com.fishmatrix.game.entity.GameTournamentEntity;
import com.fishmatrix.game.mapper.MatchMapper;
import com.fishmatrix.game.mapper.TournamentMapper;
import com.fishmatrix.game.model.MatchRecordPreview;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 比赛记录列表：公开接口只返回预览（对阵、比分、状态），不返回小局与落子，
 * 详情仍走 {@code /api/v1/game/matches/{id}}。
 */
@RequiredArgsConstructor
@Service
public class GameRecordService {

    private static final int MAX_LIMIT = 50;

    private final MatchMapper matches;
    private final TournamentMapper tournaments;

    /** 公开列表：只含管理员设为可见的记录，按展示权重与创建时间倒序。 */
    public List<MatchRecordPreview> list(int limit) {
        return previews(false, limit);
    }

    /** 管理台列表：包含被隐藏的记录。 */
    public List<MatchRecordPreview> listForAdmin(int limit) {
        return previews(true, limit);
    }

    List<MatchRecordPreview> previews(boolean includeHidden, int limit) {
        int bounded = Math.max(1, Math.min(MAX_LIMIT, limit));
        List<GameMatchEntity> rows = matches.selectRecordMatches(includeHidden, bounded);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, GameTournamentEntity> tournamentById = loadTournaments(rows);
        List<MatchRecordPreview> previews = new ArrayList<>(rows.size());
        for (GameMatchEntity row : rows) {
            previews.add(AdminConverters.toPreview(row, tournamentById.get(row.getTournamentId())));
        }
        return previews;
    }

    private Map<String, GameTournamentEntity> loadTournaments(List<GameMatchEntity> rows) {
        Set<String> ids = new LinkedHashSet<>();
        for (GameMatchEntity row : rows) {
            if (row.getTournamentId() != null) {
                ids.add(row.getTournamentId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return tournaments.selectList(Wrappers.<GameTournamentEntity>lambdaQuery()
                        .in(GameTournamentEntity::getId, ids))
                .stream()
                .collect(Collectors.toMap(GameTournamentEntity::getId, Function.identity(),
                        (first, second) -> first));
    }
}
