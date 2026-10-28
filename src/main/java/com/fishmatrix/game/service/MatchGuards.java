package com.fishmatrix.game.service;

import com.fishmatrix.game.converter.MatchConverters;
import com.fishmatrix.game.mapper.MatchMapper;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchRecord;
import java.util.Optional;

/**
 * 比赛查询与参赛校验的公共入口。
 */
final class MatchGuards {

    private MatchGuards() {
    }

    static MatchRecord requireMatch(MatchMapper matches, String matchId) {
        return Optional.ofNullable(matches.selectById(matchId))
                .map(MatchConverters::toRecord)
                .orElseThrow(() -> GameException.notFound("比赛不存在"));
    }

    static MatchRecord requireParticipant(
            MatchMapper matches, GameIdentity user, String matchId) {
        MatchRecord match = requireMatch(matches, matchId);
        if (!isParticipant(match, user.userId())) {
            throw GameException.forbidden("只有参赛选手可以操作比赛");
        }
        return match;
    }

    static boolean isParticipant(MatchRecord match, String userId) {
        return userId != null
                && (userId.equals(match.player1UserId()) || userId.equals(match.player2UserId()));
    }
}
