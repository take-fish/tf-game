package com.fishmatrix.game.service;

import com.fishmatrix.game.arena.ArenaGames;
import com.fishmatrix.game.arena.ArenaService;
import com.fishmatrix.game.model.BlindPieceView;
import com.fishmatrix.game.model.GameIds;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.model.RoundRecord;
import com.fishmatrix.game.model.RoundView;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.store.BlindXiangqiRepository;
import com.fishmatrix.game.store.MatchRepository;
import com.fishmatrix.game.store.TournamentRepository;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 对局视图组装：比赛、小局与盲棋棋盘的对外展示形态，不含业务决策。
 */
@RequiredArgsConstructor
@Component
class MatchViewAssembler {

    private final MatchRepository matches;
    private final TournamentRepository tournaments;
    private final BlindXiangqiRepository blindPieces;
    private final ArenaService arena;

    /** 比赛详情：附带最新一局的小局视图。 */
    MatchView detail(MatchRecord match) {
        TournamentRecord tournament = tournamentOf(match);
        RoundRecord round = matches.latestRound(match.id()).orElse(null);
        return toView(match, tournament, round == null ? null : toRoundView(round, tournament));
    }

    MatchView toView(MatchRecord match, TournamentRecord tournament, RoundView round) {
        String kind = tournament == null ? TournamentService.KIND_TOURNAMENT : tournament.kind();
        String gameId = round != null && round.gameId() != null
                ? round.gameId()
                : (tournament == null ? GameIds.HEATMAP_GOMOKU : tournament.gameId());
        String blindLayout = tournament == null ? null : tournament.blindLayout();
        return new MatchView(
                match.id(), match.tournamentId(), gameId, kind, match.roundNo(), match.slotNo(),
                match.stage(), match.status(), match.player1UserId(), match.player1Nickname(),
                match.player2UserId(), match.player2Nickname(), match.player1Score(),
                match.player2Score(), match.winnerUserId(), match.nextMatchId(),
                match.firstGameId(), match.gameChooserUserId(),
                match.createdAt(), match.startedAt(), match.finishedAt(), round, blindLayout);
    }

    RoundView toRoundView(RoundRecord round, TournamentRecord tournament) {
        List<String> ready = round.readyUserIds() == null || round.readyUserIds().isBlank()
                ? List.of()
                : Arrays.stream(round.readyUserIds().split(","))
                        .map(String::strip)
                        .filter(value -> !value.isEmpty())
                        .toList();
        List<BlindPieceView> blindBoard = List.of();
        if (tournament != null && GameIds.BLIND_XIANGQI.equals(tournament.gameId())) {
            blindBoard = blindPieces.listAlivePieces(round.id()).stream()
                    .map(piece -> piece.revealed()
                            ? BlindPieceView.revealed(
                                    piece.x(), piece.y(), piece.color(), piece.type())
                            : BlindPieceView.hidden(piece.x(), piece.y()))
                    .toList();
        }
        return new RoundView(
                round.id(), round.gameId(), round.gameNo(), round.status(),
                round.blackUserId(), round.whiteUserId(), round.attackerUserId(),
                round.turnUserId(), round.winnerUserId(),
                round.moveCount(), round.lastMoveAt(), round.restUntil(), ready,
                round.startedAt(), round.endsAt(), round.finishedAt(),
                round.stateVersion(), arena.stateView(round),
                ArenaGames.isArena(round.gameId())
                        ? List.of() : matches.listMoves(round.id()),
                blindBoard, round.blackUserId(), round.whiteUserId());
    }

    TournamentRecord tournamentOf(MatchRecord match) {
        return tournaments.findById(match.tournamentId()).orElse(null);
    }
}
