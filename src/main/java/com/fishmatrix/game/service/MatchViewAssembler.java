package com.fishmatrix.game.service;

import com.fishmatrix.game.arena.ArenaGames;
import com.fishmatrix.game.arena.ArenaService;
import com.fishmatrix.game.converter.BlindPieceConverters;
import com.fishmatrix.game.converter.MatchConverters;
import com.fishmatrix.game.converter.MoveConverters;
import com.fishmatrix.game.converter.TournamentConverters;
import com.fishmatrix.game.mapper.GameBlindPieceMapper;
import com.fishmatrix.game.mapper.MatchMapper;
import com.fishmatrix.game.mapper.TournamentMapper;
import com.fishmatrix.game.model.BlindPieceView;
import com.fishmatrix.game.model.GameIds;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.model.RoundRecord;
import com.fishmatrix.game.model.RoundView;
import com.fishmatrix.game.model.TournamentRecord;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 对局视图组装：比赛、小局与盲棋棋盘的对外展示形态，不含业务决策。
 */
@RequiredArgsConstructor
@Component
class MatchViewAssembler {

    private final MatchMapper matches;
    private final TournamentMapper tournaments;
    private final GameBlindPieceMapper blindPieces;
    private final ArenaService arena;

    /** 比赛详情：附带最新一局的小局视图。 */
    MatchView detail(MatchRecord match) {
        TournamentRecord tournament = tournamentOf(match);
        RoundRecord round = Optional.ofNullable(matches.selectLatestRound(match.id()))
                .map(MatchConverters::toRecord)
                .orElse(null);
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
            blindBoard = BlindPieceConverters.toPieces(
                            blindPieces.selectAlivePieces(round.id())).stream()
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
                        ? List.of()
                        : MoveConverters.views(matches.selectMoves(round.id())),
                blindBoard, round.blackUserId(), round.whiteUserId());
    }

    TournamentRecord tournamentOf(MatchRecord match) {
        return Optional.ofNullable(tournaments.selectActiveById(match.tournamentId()))
                .map(TournamentConverters::toRecord)
                .orElse(null);
    }
}
