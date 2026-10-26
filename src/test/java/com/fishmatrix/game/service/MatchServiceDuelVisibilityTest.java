package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.store.MatchRepository;
import com.fishmatrix.game.store.TournamentRepository;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class MatchServiceDuelVisibilityTest {

    private MatchService service;
    private TournamentRepository tournaments;

    @BeforeEach
    void setUp() {
        MatchRepository matches = mock(MatchRepository.class);
        tournaments = mock(TournamentRepository.class);
        when(matches.findById("m1")).thenReturn(Optional.of(match()));
        when(matches.latestRound("m1")).thenReturn(Optional.empty());
        service = new MatchService(
                matches,
                tournaments,
                mock(GameSessionRegistry.class),
                mock(GameNotificationService.class),
                mock(ApplicationEventPublisher.class),
                new GameProperties(2, 16, 300, 60, 30, 60, 60, 600, 1000, "*"));
    }

    @Test
    void duelMatchIsHiddenFromThirdParty() {
        when(tournaments.findById("t1")).thenReturn(Optional.of(tournament("DUEL")));

        assertThatThrownBy(() -> service.detailForUser(identity("u3"), "m1"))
                .isInstanceOf(GameException.class)
                .hasMessage("约战仅双方可见");
    }

    @Test
    void duelParticipantSeesKind() {
        when(tournaments.findById("t1")).thenReturn(Optional.of(tournament("DUEL")));

        MatchView view = service.detailForUser(identity("u1"), "m1");

        assertThat(view.kind()).isEqualTo(TournamentService.KIND_DUEL);
    }

    @Test
    void tournamentMatchRemainsPublic() {
        when(tournaments.findById("t1")).thenReturn(Optional.of(tournament("TOURNAMENT")));

        MatchView view = service.detailForUser(identity("u3"), "m1");

        assertThat(view.kind()).isEqualTo(TournamentService.KIND_TOURNAMENT);
    }

    private GameIdentity identity(String userId) {
        long accountId = Long.parseLong(userId.substring(1));
        return new GameIdentity(accountId, userId, "用户" + accountId, "USER");
    }

    private MatchRecord match() {
        return new MatchRecord(
                "m1", "t1", 1, 1, "KNOCKOUT", "READY",
                "u1", "甲", "u2", "乙", 0, 0,
                null, null, 1_000L, null, null);
    }

    private TournamentRecord tournament(String kind) {
        return new TournamentRecord(
                "t1", "对局", "heatmap-gomoku", kind, "BO1", "KNOCKOUT",
                "RUNNING", "u1", "甲", 2, 10_000L, 1, 1,
                null, null, 1_000L, 2_000L, null);
    }
}
