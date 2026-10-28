package com.fishmatrix.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fishmatrix.game.arena.ArenaService;
import com.fishmatrix.game.config.GameProperties;
import com.fishmatrix.game.converter.BlindPieceConverters;
import com.fishmatrix.game.converter.MatchConverters;
import com.fishmatrix.game.converter.MoveConverters;
import com.fishmatrix.game.converter.TournamentConverters;
import com.fishmatrix.game.entity.GameBlindPieceEntity;
import com.fishmatrix.game.entity.GameMoveEntity;
import com.fishmatrix.game.entity.GameRoundEntity;
import com.fishmatrix.game.mapper.GameBlindPieceMapper;
import com.fishmatrix.game.mapper.MatchMapper;
import com.fishmatrix.game.mapper.TournamentMapper;
import com.fishmatrix.game.model.BlindColor;
import com.fishmatrix.game.model.BlindPieceType;
import com.fishmatrix.game.model.BlindPieceView;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchFinishedEvent;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.model.MoveView;
import com.fishmatrix.game.model.RoundRecord;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.service.BlindXiangqiEngine.Piece;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class MatchServiceBlindTest {

    private MatchMapper matches;
    private TournamentMapper tournaments;
    private GameBlindPieceMapper blindPieces;
    private GameSessionRegistry registry;
    private ApplicationEventPublisher publisher;
    private MatchService service;

    @BeforeEach
    void setUp() {
        matches = mock(MatchMapper.class);
        tournaments = mock(TournamentMapper.class);
        blindPieces = mock(GameBlindPieceMapper.class);
        registry = mock(GameSessionRegistry.class);
        publisher = mock(ApplicationEventPublisher.class);

        MatchRecord match = new MatchRecord(
                "m1", "t1", 1, 1, "KNOCKOUT", "RUNNING",
                "u1", "甲", "u2", "乙", 0, 0, null, null,
                1_000L, null, null);
        RoundRecord round = new RoundRecord(
                "r1", "m1", "blind-xiangqi", 1, "RUNNING",
                "u1", "u2", null, "u1", null,
                0, 1_000L, null, "", null, 0, 1_000L, null, null);
        TournamentRecord tournament = new TournamentRecord(
                "t1", "盲棋", "blind-xiangqi", "TOURNAMENT", "BO1", "TBD", "RUNNING",
                "u1", "甲", 2, 10_000L, 1, 1, null, null,
                null, null, 1_000L, 2_000L, null, "STANDARD_SHUFFLE", null);

        when(matches.selectById("m1")).thenReturn(MatchConverters.toEntity(match));
        when(matches.selectLatestRound("m1")).thenReturn(MatchConverters.toEntity(round));
        when(matches.selectMoves("r1")).thenReturn(List.of());
        when(tournaments.selectActiveById("t1"))
                .thenReturn(TournamentConverters.toEntity(tournament));
        when(blindPieces.selectAlivePieces("r1")).thenReturn(
                BlindPieceConverters.toEntities("r1", List.of(
                        new Piece("p1", BlindColor.RED, BlindPieceType.CHARIOT, 0, 9, false),
                        new Piece("p2", BlindColor.BLACK, BlindPieceType.SOLDIER, 4, 4, false),
                        new Piece("p3", BlindColor.RED, BlindPieceType.GENERAL, 4, 9, false),
                        new Piece("p4", BlindColor.BLACK, BlindPieceType.GENERAL, 4, 0, false))));
        when(matches.updateTurnAndCount(
                anyString(), anyInt(), anyInt(), anyString(), any())).thenReturn(1);
        when(blindPieces.reveal(anyString(), anyString())).thenReturn(1);
        when(blindPieces.move(anyString(), anyString(), anyInt(), anyInt())).thenReturn(1);
        when(blindPieces.capture(anyString(), anyString())).thenReturn(1);
        when(matches.finishIfRunning(
                anyString(), anyString(), anyInt(), anyInt(), any())).thenReturn(1);
        when(matches.restAfterGame(
                anyString(), any(), any(), any())).thenReturn(1);

        service = new MatchService(
                matches, tournaments, blindPieces, registry,
                mock(GameNotificationService.class), publisher,
                new GameProperties(2, 16, 300, 60, 30, 60, 60, 600, 1000, "*"),
                mock(ArenaService.class), mock(ExerciseService.class));
    }

    @Test
    void revealPieceFlipsAndPassesTurn() {
        service.revealPiece(identity("u1"), "m1", 0, 9);

        verify(blindPieces).reveal("r1", "p1");
        verify(matches).updateTurnAndCount(eq("r1"), eq(0), eq(1), eq("u2"), any());
        GameMoveEntity stored = storedMove();
        assertThat(stored.getRoundId()).isEqualTo("r1");
        MoveView move = MoveConverters.toView(stored);
        assertThat(move.moveNo()).isEqualTo(1);
        assertThat(move.userId()).isEqualTo("u1");
        assertThat(move.x()).isZero();
        assertThat(move.y()).isEqualTo(9);
        assertThat(move.action()).isEqualTo("REVEAL");
        assertThat(move.fromX()).isNull();
        assertThat(move.fromY()).isNull();
        assertThat(move.pieceType()).isEqualTo("CHARIOT");
        assertThat(move.pieceColor()).isEqualTo("RED");
        assertThat(move.capturedPieceType()).isNull();
        assertThat(move.capturedPieceColor()).isNull();
        verify(matches, never()).finishIfRunning(
                anyString(), anyString(), anyInt(), anyInt(), any());

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
        when(blindPieces.selectAlivePieces("r1")).thenReturn(
                BlindPieceConverters.toEntities("r1", List.of(
                        new Piece("p1", BlindColor.RED, BlindPieceType.CHARIOT, 0, 9, true),
                        new Piece("p2", BlindColor.BLACK, BlindPieceType.SOLDIER, 4, 4, false),
                        new Piece("p3", BlindColor.RED, BlindPieceType.GENERAL, 4, 9, false),
                        new Piece("p4", BlindColor.BLACK, BlindPieceType.GENERAL, 4, 0, false))));

        service.movePiece(identity("u1"), "m1", 0, 9, 0, 8);

        verify(blindPieces).move("r1", "p1", 0, 8);
        verify(blindPieces, never()).capture(anyString(), anyString());
        MoveView move = MoveConverters.toView(storedMove());
        assertThat(move.moveNo()).isEqualTo(1);
        assertThat(move.userId()).isEqualTo("u1");
        assertThat(move.x()).isZero();
        assertThat(move.y()).isEqualTo(8);
        assertThat(move.action()).isEqualTo("MOVE");
        assertThat(move.fromX()).isZero();
        assertThat(move.fromY()).isEqualTo(9);
        assertThat(move.pieceType()).isEqualTo("CHARIOT");
        assertThat(move.pieceColor()).isEqualTo("RED");
        assertThat(move.capturedPieceType()).isNull();
        assertThat(move.capturedPieceColor()).isNull();

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
        when(blindPieces.selectAlivePieces("r1")).thenReturn(
                BlindPieceConverters.toEntities("r1", List.of(
                        new Piece("p1", BlindColor.RED, BlindPieceType.CHARIOT, 0, 9, true),
                        new Piece("p2", BlindColor.RED, BlindPieceType.SOLDIER, 0, 5, false),
                        new Piece("p3", BlindColor.RED, BlindPieceType.GENERAL, 4, 9, false),
                        new Piece("p4", BlindColor.BLACK, BlindPieceType.GENERAL, 4, 0, false))));

        service.movePiece(identity("u1"), "m1", 0, 9, 0, 5);

        verify(blindPieces).capture("r1", "p2");
        MoveView move = MoveConverters.toView(storedMove());
        assertThat(move.x()).isZero();
        assertThat(move.y()).isEqualTo(5);
        assertThat(move.action()).isEqualTo("MOVE");
        assertThat(move.fromX()).isZero();
        assertThat(move.fromY()).isEqualTo(9);
        assertThat(move.pieceType()).isEqualTo("CHARIOT");
        assertThat(move.pieceColor()).isEqualTo("RED");
        assertThat(move.capturedPieceType()).isEqualTo("SOLDIER");
        assertThat(move.capturedPieceColor()).isEqualTo("RED");
        verify(matches, never()).finishIfRunning(
                anyString(), anyString(), anyInt(), anyInt(), any());

        Map<String, Object> action = frame("blind_action");
        assertThat(action)
                .containsEntry("capturedPieceType", "SOLDIER")
                .containsEntry("capturedPieceColor", "RED");
        assertThat(action.get("result")).isNull();
    }

    @Test
    void blindCaptureOfEnemyGeneralEndsMatch() {
        when(blindPieces.selectAlivePieces("r1")).thenReturn(
                BlindPieceConverters.toEntities("r1", List.of(
                        new Piece("p1", BlindColor.RED, BlindPieceType.CHARIOT, 0, 9, true),
                        new Piece("p2", BlindColor.BLACK, BlindPieceType.GENERAL, 0, 5, false),
                        new Piece("p3", BlindColor.RED, BlindPieceType.GENERAL, 4, 9, false))));

        service.movePiece(identity("u1"), "m1", 0, 9, 0, 5);

        verify(blindPieces).capture("r1", "p2");
        verify(matches).finishIfRunning(eq("m1"), eq("u1"), eq(1), eq(0), any());
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
    void facingGeneralsFallsBackToHigherSeedAndRestsForNextGame() {
        when(blindPieces.selectAlivePieces("r1")).thenReturn(
                BlindPieceConverters.toEntities("r1", List.of(
                        new Piece("p1", BlindColor.RED, BlindPieceType.GENERAL, 4, 8, true),
                        new Piece("p2", BlindColor.BLACK, BlindPieceType.GENERAL, 4, 1, false),
                        new Piece("p3", BlindColor.RED, BlindPieceType.CHARIOT, 0, 9, true))));

        service.revealPiece(identity("u1"), "m1", 4, 1);

        verify(blindPieces).reveal("r1", "p2");
        // 赛事只认输赢：和棋按种子顺位判胜，默认 1 号位 u1 晋级本局。
        verify(matches).restAfterGame(eq("r1"), eq("u1"), any(), any());
        verify(matches).updateScore("m1", 1, 0);
        verify(matches, never()).finishIfRunning(
                anyString(), anyString(), anyInt(), anyInt(), any());
        verify(publisher, never()).publishEvent(any(MatchFinishedEvent.class));

        assertThat(frame("blind_action")).containsEntry("result", "DRAW");
        Map<String, Object> rest = frame("match_rest");
        assertThat(rest)
                .containsEntry("player1Score", 1)
                .containsEntry("player2Score", 0);
    }

    @Test
    void blindBo3FirstWinRestsInsteadOfEndingMatch() {
        when(tournaments.selectActiveById("t1")).thenReturn(TournamentConverters.toEntity(
                new TournamentRecord(
                        "t1", "盲棋", "blind-xiangqi", "TOURNAMENT", "BO3", "TBD", "RUNNING",
                        "u1", "甲", 2, 10_000L, 1, 3, null, null,
                        null, null, 1_000L, 2_000L, null, "STANDARD_SHUFFLE", null)));
        when(blindPieces.selectAlivePieces("r1")).thenReturn(
                BlindPieceConverters.toEntities("r1", List.of(
                        new Piece("p1", BlindColor.RED, BlindPieceType.CHARIOT, 0, 9, true),
                        new Piece("p2", BlindColor.BLACK, BlindPieceType.GENERAL, 0, 5, false),
                        new Piece("p3", BlindColor.RED, BlindPieceType.GENERAL, 4, 9, false))));

        service.movePiece(identity("u1"), "m1", 0, 9, 0, 5);

        verify(matches).restAfterGame(eq("r1"), eq("u1"), any(), any());
        verify(matches).updateScore("m1", 1, 0);
        verify(matches, never()).finishIfRunning(
                anyString(), anyString(), anyInt(), anyInt(), any());
        Map<String, Object> rest = frame("match_rest");
        assertThat(rest)
                .containsEntry("player1Score", 1)
                .containsEntry("player2Score", 0);
    }

    @Test
    void startNextGameCreatesFreshBlindRoundAndAlternatesFirstPlayer() {
        RoundRecord rest = new RoundRecord(
                "r1", "m1", "blind-xiangqi", 1, "REST",
                "u1", "u2", null, "u2", null,
                5, 1_000L, 2_000L, "", null, 0, 1_000L, null, null);
        when(matches.selectLatestRound("m1")).thenReturn(MatchConverters.toEntity(rest));
        when(matches.finishRestRound(eq("r1"), any())).thenReturn(1);
        when(registry.byUser(anyString())).thenReturn(List.of());

        service.startNextGame("m1");

        ArgumentCaptor<GameRoundEntity> roundCaptor =
                ArgumentCaptor.forClass(GameRoundEntity.class);
        verify(matches).insertRound(roundCaptor.capture());
        RoundRecord next = MatchConverters.toRecord(roundCaptor.getValue());
        assertThat(next.gameNo()).isEqualTo(2);
        assertThat(next.status()).isEqualTo("RUNNING");
        assertThat(next.blackUserId()).isEqualTo("u2");
        assertThat(next.whiteUserId()).isEqualTo("u1");
        assertThat(next.turnUserId()).isEqualTo("u2");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<GameBlindPieceEntity>> piecesCaptor =
                ArgumentCaptor.forClass((Class) Collection.class);
        verify(blindPieces).insert(piecesCaptor.capture());
        assertThat(BlindPieceConverters.toPieces(new ArrayList<>(piecesCaptor.getValue())))
                .hasSize(32)
                .allSatisfy(piece -> assertThat(piece.revealed()).isFalse());
    }

    @Test
    void blindBoardHidesFaceDownIdentity() {
        when(blindPieces.selectAlivePieces("r1")).thenReturn(
                BlindPieceConverters.toEntities("r1", List.of(
                        new Piece("p1", BlindColor.RED, BlindPieceType.CHARIOT, 0, 9, false),
                        new Piece("p2", BlindColor.BLACK, BlindPieceType.GENERAL, 4, 1, true))));

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
        when(tournaments.selectActiveById("t1")).thenReturn(TournamentConverters.toEntity(
                new TournamentRecord(
                        "t1", "热力五子棋", "heatmap-gomoku", "TOURNAMENT", "BO1", "TBD", "RUNNING",
                        "u1", "甲", 2, 10_000L, 1, 1, null, null,
                        null, null, 1_000L, 2_000L, null, null, null)));

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

    private GameMoveEntity storedMove() {
        ArgumentCaptor<GameMoveEntity> captor = ArgumentCaptor.forClass(GameMoveEntity.class);
        verify(matches).insertMove(captor.capture());
        return captor.getValue();
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
