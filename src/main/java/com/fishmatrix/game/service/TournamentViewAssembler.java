package com.fishmatrix.game.service;

import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.model.PlayerView;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.model.TournamentView;
import com.fishmatrix.game.store.MatchRepository;
import com.fishmatrix.game.store.TournamentRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 赛事视图组装：把赛事与对阵记录转换为对外视图，不含业务决策。
 */
@RequiredArgsConstructor
@Component
class TournamentViewAssembler {

    private final TournamentRepository tournaments;
    private final MatchRepository matches;

    TournamentView view(TournamentRecord tournament) {
        List<PlayerView> players = tournaments.listPlayers(tournament.id()).stream()
                .map(player -> new PlayerView(
                        player.userId(), player.nickname(), player.seed(), player.status()))
                .toList();
        List<MatchView> views = matches.listByTournament(tournament.id()).stream()
                .map(match -> matchView(match, tournament))
                .toList();
        return new TournamentView(
                tournament.id(), tournament.name(), tournament.gameId(), tournament.kind(),
                tournament.format(),
                tournament.bracketType(), tournament.status(), tournament.creatorUserId(),
                tournament.creatorNickname(), tournament.targetPlayers(), tournament.inviteDeadline(),
                tournament.currentRound(), tournament.totalRounds(), tournament.championUserId(),
                tournament.championNickname(), tournament.runnerUpUserId(),
                tournament.runnerUpNickname(), tournament.createdAt(), tournament.startedAt(),
                tournament.finishedAt(), players, views, tournament.blindLayout(),
                tournament.gamePool());
    }

    MatchView matchView(MatchRecord match, TournamentRecord tournament) {
        String effectiveGameId = match.firstGameId() == null
                ? tournament.gameId() : match.firstGameId();
        return new MatchView(
                match.id(), match.tournamentId(), effectiveGameId, tournament.kind(),
                match.roundNo(), match.slotNo(), match.stage(), match.status(),
                match.player1UserId(), match.player1Nickname(),
                match.player2UserId(), match.player2Nickname(), match.player1Score(),
                match.player2Score(), match.winnerUserId(), match.nextMatchId(),
                match.firstGameId(), match.gameChooserUserId(),
                match.createdAt(), match.startedAt(), match.finishedAt(), null,
                tournament.blindLayout());
    }
}
