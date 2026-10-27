package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.model.BlindColor;
import com.fishmatrix.game.model.BlindPieceType;
import com.fishmatrix.game.model.BlindPieceView;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchFinishedEvent;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.model.RoundRecord;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.service.BlindXiangqiEngine.Piece;
import com.fishmatrix.game.store.BlindXiangqiRepository;
import com.fishmatrix.game.store.MatchRepository;
import com.fishmatrix.game.store.TournamentRepository;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class MatchServiceBlindTest {

    private MatchRepository matches;
    private TournamentRepository tournaments;
    private BlindXiangqiRepository blindPieces;
    private GameSessionRegistry registry;
    private ApplicationEventPublisher publisher;
    private MatchService service;

    @BeforeEach
    void setUp() {
        matches = mock(MatchRepository.class);
        tournaments = mock(TournamentRepository.class);
        blindPieces = mock(BlindXiangqiRepository.class);
        registry = mock(GameSessionRegistry.class);
        publisher = mock(ApplicationEventPublisher.class);

        MatchRecord match = new MatchRecord(
                "m1", "t1", 1, 1, "KNOCKOUT", "RUNNING",
                "u1", "甲", "u2", "乙", 0, 0, null, null,
                1_000L, null, null);
        RoundRecord round = new RoundRecord(
                "r1", "m1", 1, "RUNNING", "u1", "u2", "u1", null,
                0, 1_000L, null, "", 1_000L, null);
        TournamentRecord tournament = new TournamentRecord(
                "t1", "盲棋", "blind-xiangqi", "TOURNAMENT", "BO1", "TBD", "RUNNING",
                "u1", "甲", 2, 10_000L, 1, 1, null, null,
                1_000L, 2_000L, null, "STANDARD_SHUFFLE");

        when(matches.findById("m1")).thenReturn(Optional.of(match));
        when(matches.latestRound("m1")).thenReturn(Optional.of(round));
        when(matches.listMoves("r1")).thenReturn(List.of());
        when(tournaments.findById("t1")).thenReturn(Optional.of(tournament));
        when(blindPieces.listAlivePieces("r1")).thenReturn(List.of(
                new Piece("p1", BlindColor.RED, BlindPieceType.CHARIOT, 0, 9, false),
                new Piece("p2", BlindColor.BLACK, BlindPieceType.SOLDIER, 4, 4, false),
                new Piece("p3", BlindColor.RED, BlindPieceType.GENERAL, 4, 9, false),
                new Piece("p4", BlindColor.BLACK, BlindPieceType.GENERAL, 4, 0, false)));
        when(matches.updateTurnAndCount(
                anyString(), anyInt(), anyInt(), anyString(), anyLong())).thenReturn(1);
        when(blindPieces.reveal(anyString(), anyString())).thenReturn(1);
        when(blindPieces.move(anyString(), anyString(), anyInt(), anyInt())).thenReturn(1);
        when(blindPieces.capture(anyString(), anyString())).thenReturn(1);
        when(matches.finishIfRunning(
                anyString(), anyString(), anyInt(), anyInt(), anyLong())).thenReturn(1);
        when(matches.restAfterGame(
                anyString(), nullable(String.class), anyLong(), anyLong())).thenReturn(1);

        service = new MatchService(
                matches, tournaments, blindPieces, registry,
                mock(GameNotificationService.class), publisher,
                new GameProperties(2, 16, 300, 60, 30, 60, 60, 600, 1000, "*"));
    }

    @Test
    void revealPieceFlipsAndPassesTurn() {
        service.revealPiece(identity("u1"), "m1", 0, 9);

        verify(blindPieces).reveal("r1", "p1");
        verify(matches).updateTurnAndCount(eq("r1"), eq(0), eq(1), eq("u2"), anyLong());
        verify(matches).insertMove(
                eq("r1"), eq(1), eq("u1"), eq(0), eq(9), eq("REVEAL"),
                isNull(), isNull(), eq("CHARIOT"), eq("RED"),
                isNull(), isNull(), anyLong());
        verify(matches, never()).finishIfRunning(
                anyString(), anyString(), anyInt(), anyInt(), anyLong());

        Map<String, Object> action = frame("blind_action");
        assertThat(action)
                .containsEntry("action", "REVEAL")
                .containsEntry("pieceType", "CHARIOT")
                .containsEntry("pieceColor", "RED")
                .containsEntry("turnUserId", "u2")
                .containsEntry("moveCount", 1);
        assertThat(action.get("result")).isNull();
    }

    @Test
    void movePieceMovesRevealedOwnPiece() {
        when(blindPieces.listAlivePieces("r1")).thenReturn(List.of(
                new Piece("p1", BlindColor.RED, BlindPieceType.CHARIOT, 0, 9, true),
                new Piece("p2", BlindColor.BLACK, BlindPieceType.SOLDIER, 4, 4, false),
                new Piece("p3", BlindColor.RED, BlindPieceType.GENERAL, 4, 9, false),
                new Piece("p4", BlindColor.BLACK, BlindPieceType.GENERAL, 4, 0, false)));

        service.movePiece(identity("u1"), "m1", 0, 9, 0, 8);

        verify(blindPieces).move("r1", "p1", 0, 8);
        verify(blindPieces, never()).capture(anyString(), anyString());
        verify(matches).insertMove(
                eq("r1"), eq(1), eq("u1"), eq(0), eq(8), eq("MOVE"),
                eq(0), eq(9), eq("CHARIOT"), eq("RED"),
                isNull(), isNull(), anyLong());

        Map<String, Object> action = frame("blind_action");
        assertThat(action)
                .containsEntry("action", "MOVE")
                .containsEntry("fromX", 0)
                .containsEntry("fromY", 9)
                .containsEntry("toX", 0)
                .containsEntry("toY", 8)
                .containsEntry("turnUserId", "u2");
    }

    @Test
    void blindCaptureAcceptsOwnFaceDownPiece() {
        when(blindPieces.listAlivePieces("r1")).thenReturn(List.of(
                new Piece("p1", BlindColor.RED, BlindPieceType.CHARIOT, 0, 9, true),
                new Piece("p2", BlindColor.RED, BlindPieceType.SOLDIER, 0, 5, false),
                new Piece("p3", BlindColor.RED, BlindPieceType.GENERAL, 4, 9, false),
                new Piece("p4", BlindColor.BLACK, BlindPieceType.GENERAL, 4, 0, false)));

        service.movePiece(identity("u1"), "m1", 0, 9, 0, 5);

        verify(blindPieces).capture("r1", "p2");
        verify(matches).insertMove(
                eq("r1"), eq(1), eq("u1"), eq(0), eq(5), eq("MOVE"),
                eq(0), eq(9), eq("CHARIOT"), eq("RED"),
                eq("SOLDIER"), eq("RED"), anyLong());
        verify(matches, never()).finishIfRunning(
                anyString(), anyString(), anyInt(), anyInt(), anyLong());

        Map<String, Object> action = frame("blind_action");
        assertThat(action)
                .containsEntry("capturedPieceType", "SOLDIER")
                .containsEntry("capturedPieceColor", "RED");
        assertThat(action.get("result")).isNull();
    }

    @Test
    void blindCaptureOfEnemyGeneralEndsMatch() {
        when(blindPieces.listAlivePieces("r1")).thenReturn(List.of(
                new Piece("p1", BlindColor.RED, BlindPieceType.CHARIOT, 0, 9, true),
                new Piece("p2", BlindColor.BLACK, BlindPieceType.GENERAL, 0, 5, false),
                new Piece("p3", BlindColor.RED, BlindPieceType.GENERAL, 4, 9, false)));

        service.movePiece(identity("u1"), "m1", 0, 9, 0, 5);

        verify(blindPieces).capture("r1", "p2");
        verify(matches).finishIfRunning(eq("m1"), eq("u1"), eq(1), eq(0), anyLong());
        verify(publisher).publishEvent(any(MatchFinishedEvent.class));
        assertThat(frame("blind_action")).containsEntry("result", "WIN");
        Map<String, Object> result = frame("match_result");
        assertThat(result)
                .containsEntry("winnerUserId", "u1")
                .containsEntry("reason", "将帅被吃")
                .containsEntry("player1Score", 1)
                .containsEntry("player2Score", 0);
    }

    @Test
    void facingGeneralsRecordsDrawAndRestsForNextGame() {
        when(blindPieces.listAlivePieces("r1")).thenReturn(List.of(
                new Piece("p1", BlindColor.RED, BlindPieceType.GENERAL, 4, 8, true),
                new Piece("p2", BlindColor.BLACK, BlindPieceType.GENERAL, 4, 1, false),
                new Piece("p3", BlindColor.RED, BlindPieceType.CHARIOT, 0, 9, true)));

        service.revealPiece(identity("u1"), "m1", 4, 1);

        verify(blindPieces).reveal("r1", "p2");
        verify(matches).restAfterGame(eq("r1"), isNull(), anyLong(), anyLong());
        verify(matches).updateScore("m1", 0, 0);
        verify(matches, never()).finishIfRunning(
                anyString(), anyString(), anyInt(), anyInt(), anyLong());
        verify(publisher, never()).publishEvent(any(MatchFinishedEvent.class));

        assertThat(frame("blind_action")).containsEntry("result", "DRAW");
        Map<String, Object> rest = frame("match_rest");
        assertThat(rest)
                .containsEntry("player1Score", 0)
                .containsEntry("player2Score", 0);
    }

    @Test
    void blindBo3FirstWinRestsInsteadOfEndingMatch() {
        when(tournaments.findById("t1")).thenReturn(Optional.of(new TournamentRecord(
                "t1", "盲棋", "blind-xiangqi", "TOURNAMENT", "BO3", "TBD", "RUNNING",
                "u1", "甲", 2, 10_000L, 1, 3, null, null,
                1_000L, 2_000L, null, "STANDARD_SHUFFLE")));
        when(blindPieces.listAlivePieces("r1")).thenReturn(List.of(
                new Piece("p1", BlindColor.RED, BlindPieceType.CHARIOT, 0, 9, true),
                new Piece("p2", BlindColor.BLACK, BlindPieceType.GENERAL, 0, 5, false),
                new Piece("p3", BlindColor.RED, BlindPieceType.GENERAL, 4, 9, false)));

        service.movePiece(identity("u1"), "m1", 0, 9, 0, 5);

        verify(matches).restAfterGame(eq("r1"), eq("u1"), anyLong(), anyLong());
        verify(matches).updateScore("m1", 1, 0);
        verify(matches, never()).finishIfRunning(
                anyString(), anyString(), anyInt(), anyInt(), anyLong());
        Map<String, Object> rest = frame("match_rest");
        assertThat(rest)
                .containsEntry("player1Score", 1)
                .containsEntry("player2Score", 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void startNextGameCreatesFreshBlindRoundAndAlternatesFirstPlayer() {
        RoundRecord rest = new RoundRecord(
                "r1", "m1", 1, "REST", "u1", "u2", "u2", null,
                5, 1_000L, 2_000L, "", 1_000L, null);
        when(matches.latestRound("m1")).thenReturn(Optional.of(rest));
        when(matches.finishRestRound(eq("r1"), anyLong())).thenReturn(1);
        when(registry.byUser(anyString())).thenReturn(List.of());

        service.startNextGame("m1");

        ArgumentCaptor<RoundRecord> roundCaptor = ArgumentCaptor.forClass(RoundRecord.class);
        verify(matches).insertRound(roundCaptor.capture());
        RoundRecord next = roundCaptor.getValue();
        assertThat(next.gameNo()).isEqualTo(2);
        assertThat(next.status()).isEqualTo("RUNNING");
        assertThat(next.blackUserId()).isEqualTo("u2");
        assertThat(next.whiteUserId()).isEqualTo("u1");
        assertThat(next.turnUserId()).isEqualTo("u2");

        ArgumentCaptor<List<Piece>> piecesCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(blindPieces).insertPieces(eq(next.id()), piecesCaptor.capture());
        assertThat(piecesCaptor.getValue())
                .hasSize(32)
                .allSatisfy(piece -> assertThat(piece.revealed()).isFalse());
    }

    @Test
    void blindBoardHidesFaceDownIdentity() {
        when(blindPieces.listAlivePieces("r1")).thenReturn(List.of(
                new Piece("p1", BlindColor.RED, BlindPieceType.CHARIOT, 0, 9, false),
                new Piece("p2", BlindColor.BLACK, BlindPieceType.GENERAL, 4, 1, true)));

        MatchView view = service.detail("m1");

        assertThat(view.gameId()).isEqualTo("blind-xiangqi");
        assertThat(view.blindLayout()).isEqualTo("STANDARD_SHUFFLE");
        assertThat(view.currentRound().firstUserId()).isEqualTo("u1");
        assertThat(view.currentRound().secondUserId()).isEqualTo("u2");

        List<BlindPieceView> board = view.currentRound().blindBoard();
        assertThat(board).hasSize(2);
        BlindPieceView hidden = board.stream()
                .filter(piece -> piece.x() == 0 && piece.y() == 9)
                .findFirst()
                .orElseThrow();
        assertThat(hidden.revealed()).isFalse();
        assertThat(hidden.color()).isNull();
        assertThat(hidden.pieceType()).isNull();

        BlindPieceView revealed = board.stream()
                .filter(piece -> piece.x() == 4 && piece.y() == 1)
                .findFirst()
                .orElseThrow();
        assertThat(revealed.revealed()).isTrue();
        assertThat(revealed.color()).isEqualTo("BLACK");
        assertThat(revealed.pieceType()).isEqualTo("GENERAL");
    }

    @Test
    void blindActionRejectedForGomokuMatch() {
        when(tournaments.findById("t1")).thenReturn(Optional.of(new TournamentRecord(
                "t1", "热力五子棋", "heatmap-gomoku", "TOURNAMENT", "BO1", "TBD", "RUNNING",
                "u1", "甲", 2, 10_000L, 1, 1, null, null,
                1_000L, 2_000L, null, null)));

        assertThatThrownBy(() -> service.revealPiece(identity("u1"), "m1", 4, 4))
                .isInstanceOf(GameException.class)
                .hasMessage("当前比赛不是盲棋");
    }

    @Test
    void placeStoneRejectedForBlindMatch() {
        assertThatThrownBy(() -> service.placeStone(identity("u1"), "m1", 0, 0))
                .isInstanceOf(GameException.class)
                .hasMessage("盲棋请使用翻棋或走子操作");
    }

    @Test
    void blindActionRejectedWhenNotPlayersTurn() {
        assertThatThrownBy(() -> service.revealPiece(identity("u2"), "m1", 0, 9))
                .isInstanceOf(GameException.class)
                .hasMessage("还没轮到你操作");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> frame(String type) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(registry, atLeastOnce()).broadcastMatch(eq("m1"), anyCollection(), captor.capture());
        return captor.getAllValues().stream()
                .filter(Map.class::isInstance)
                .map(value -> (Map<String, Object>) value)
                .filter(value -> type.equals(value.get("type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 " + type + " 帧"));
    }

    private static GameIdentity identity(String userId) {
        long accountId = Long.parseLong(userId.substring(1));
        return new GameIdentity(accountId, userId, "用户" + accountId, "USER");
    }
}
