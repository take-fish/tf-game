package com.fishmatrix.game.service;

import com.fishmatrix.game.converter.TournamentConverters;
import com.fishmatrix.game.mapper.TournamentMapper;
import com.fishmatrix.game.model.TournamentPlayerRecord;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.model.TournamentView;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 赛事推送：统一封装广播范围（赛事全员 / 约战仅双方）与消息帧格式。
 */
@RequiredArgsConstructor
@Component
class TournamentBroadcaster {

    private final GameSessionRegistry registry;
    private final TournamentMapper tournaments;
    private final TournamentViewAssembler assembler;

    void broadcastTournament(TournamentRecord tournament) {
        if (TournamentService.KIND_DUEL.equals(tournament.kind())) {
            broadcastDuel(tournament, Map.of(
                    "type", "duel_update",
                    "duel", assembler.view(tournament)));
            return;
        }
        registry.broadcastAll(Map.of(
                "type", "tournament_update",
                "tournament", assembler.view(tournament)));
    }

    /** 约战只通知双方，避免任何第三方感知到对局。 */
    void broadcastDuel(TournamentRecord tournament, Map<String, Object> frame) {
        Set<String> users = TournamentConverters.playerRecords(
                        tournaments.selectPlayers(tournament.id())).stream()
                .map(TournamentPlayerRecord::userId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        registry.broadcastUsers(users, frame);
    }

    void broadcastDuelStarted(TournamentRecord tournament) {
        TournamentView view = assembler.view(tournament);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "duel_started");
        frame.put("duel", view);
        if (!view.matches().isEmpty()) {
            frame.put("match", view.matches().get(0));
        }
        broadcastDuel(tournament, frame);
    }

    void announceCancelled(TournamentRecord tournament) {
        if (TournamentService.KIND_DUEL.equals(tournament.kind())) {
            broadcastDuel(tournament, Map.of(
                    "type", "duel_update",
                    "duel", assembler.view(tournament)));
            return;
        }
        broadcastTournament(tournament);
        registry.broadcastAll(Map.of(
                "type", "tournament_cancelled",
                "tournamentId", tournament.id()));
    }
}
