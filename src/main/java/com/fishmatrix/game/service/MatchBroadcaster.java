package com.fishmatrix.game.service;

import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 对局推送：统一只广播给双方，避免观战者收到准备、落子等内部帧。
 */
@RequiredArgsConstructor
@Component
class MatchBroadcaster {

    private final GameSessionRegistry registry;
    private final MatchViewAssembler assembler;

    /** 推送最新比赛视图（match_update）。 */
    void broadcastMatch(MatchRecord match) {
        MatchView view = assembler.detail(match);
        registry.broadcastMatch(match.id(), participantIds(match), Map.of(
                "type", "match_update",
                "match", view));
    }

    void send(MatchRecord match, Map<String, Object> frame) {
        registry.broadcastMatch(match.id(), participantIds(match), frame);
    }

    static Collection<String> participantIds(MatchRecord match) {
        Set<String> ids = new LinkedHashSet<>();
        if (match.player1UserId() != null) {
            ids.add(match.player1UserId());
        }
        if (match.player2UserId() != null) {
            ids.add(match.player2UserId());
        }
        return ids;
    }
}
