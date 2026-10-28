package com.fishmatrix.game.service;

import com.fishmatrix.game.arena.ArenaGames;
import com.fishmatrix.game.converter.BlindPieceConverters;
import com.fishmatrix.game.converter.MatchConverters;
import com.fishmatrix.game.converter.MoveConverters;
import com.fishmatrix.game.converter.SqlTime;
import com.fishmatrix.game.converter.TournamentConverters;
import com.fishmatrix.game.mapper.GameBlindPieceMapper;
import com.fishmatrix.game.mapper.MatchMapper;
import com.fishmatrix.game.mapper.TournamentMapper;
import com.fishmatrix.game.model.BlindColor;
import com.fishmatrix.game.model.BlindPieceType;
import com.fishmatrix.game.model.GameException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.GameIds;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.model.MoveView;
import com.fishmatrix.game.model.RoundRecord;
import com.fishmatrix.game.model.TournamentRecord;
import com.fishmatrix.game.service.BlindXiangqiEngine.Piece;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 对局内的落子与盲棋操作：五子棋落子、盲棋翻棋/走子，以及胜负判定后的结算转交。
 */
@RequiredArgsConstructor
@Service
class MatchMoveService {

    private final MatchMapper matches;
    private final TournamentMapper tournaments;
    private final GameBlindPieceMapper blindPieces;
    private final MatchBroadcaster broadcaster;
    private final MatchViewAssembler assembler;
    private final MatchSettlementService settlement;

    MatchView placeStone(GameIdentity user, String matchId, Integer x, Integer y) {
        if (x == null || y == null) {
            throw GameException.badRequest("GAME_MOVE_INVALID", "落子坐标不能为空");
        }
        if (!GomokuEngine.inBounds(x, y)) {
            throw GameException.badRequest("GAME_MOVE_INVALID", "落子超出棋盘");
        }
        MatchRecord match = MatchGuards.requireParticipant(matches, user, matchId);
        requireGomoku(match);
        if (!"RUNNING".equals(match.status())) {
            throw GameException.conflict("GAME_MATCH_NOT_RUNNING", "比赛尚未开始");
        }
        RoundRecord round = Optional.ofNullable(matches.selectLatestRound(matchId))
                .map(MatchConverters::toRecord)
                .orElseThrow(() -> GameException.conflict("GAME_ROUND_MISSING", "对局尚未开始"));
        if (!"RUNNING".equals(round.status())) {
            throw GameException.conflict("GAME_ROUND_NOT_RUNNING", "当前小局不在对弈中");
        }
        if (ArenaGames.isArena(round.gameId())) {
            throw GameException.conflict("GAME_ARENA_NOT_STONES", "该小局为实时演练，请使用演练操作");
        }
        if (!user.userId().equals(round.turnUserId())) {
            throw GameException.forbidden("还没轮到你落子");
        }

        List<MoveView> moves = MoveConverters.views(matches.selectMoves(round.id()));
        int[][] board = GomokuEngine.board(moves, round.blackUserId());
        if (board[x][y] != GomokuEngine.EMPTY) {
            throw GameException.conflict("GAME_MOVE_OCCUPIED", "该位置已有棋子");
        }
        String opponent = opponentOf(match, user.userId());
        long now = System.currentTimeMillis();
        int nextMoveNo = round.moveCount() + 1;
        int updated = matches.updateTurnAndCount(
                round.id(), round.moveCount(), nextMoveNo, opponent, SqlTime.toDateTime(now));
        if (updated == 0) {
            throw GameException.conflict("GAME_MOVE_CONFLICT", "落子冲突，请刷新棋盘");
        }
        matches.insertMove(MoveConverters.toEntity(
                round.id(), nextMoveNo, user.userId(), x, y, now));

        board[x][y] = user.userId().equals(round.blackUserId())
                ? GomokuEngine.BLACK : GomokuEngine.WHITE;
        boolean win = GomokuEngine.isWinningMove(board, x, y);
        boolean full = !win && GomokuEngine.isFull(board);

        broadcaster.send(match, Map.of(
                "type", "stone",
                "matchId", matchId,
                "roundId", round.id(),
                "moveNo", nextMoveNo,
                "userId", user.userId(),
                "x", x,
                "y", y,
                "turnUserId", opponent,
                "moveCount", nextMoveNo,
                "winning", win));

        if (win || full) {
            settlement.finishGame(match, round, win ? user.userId() : null, win ? "五连获胜" : "和棋");
        } else {
            broadcaster.broadcastMatch(match);
        }
        return assembler.detail(MatchGuards.requireMatch(matches, matchId));
    }

    /** 盲棋翻棋：花一步揭开一枚反面棋，翻完即换对方行动。 */
    MatchView revealPiece(GameIdentity user, String matchId, Integer x, Integer y) {
        if (x == null || y == null || !BlindXiangqiEngine.inBounds(x, y)) {
            throw GameException.badRequest("GAME_MOVE_INVALID", "翻棋坐标不合法");
        }
        BlindRound state = requireBlindRound(user, matchId);
        Piece target = BlindXiangqiEngine.pieceAt(state.pieces(), x, y);
        if (target == null) {
            throw GameException.conflict("GAME_MOVE_EMPTY", "该位置没有棋子");
        }
        if (target.revealed()) {
            throw GameException.conflict("GAME_PIECE_REVEALED", "该棋子已经翻开");
        }
        return applyBlindAction(state, user, "REVEAL", target, null, null, x, y, null);
    }

    /** 盲棋走子：已翻开的己方棋按中国象棋规则移动或吃子。 */
    MatchView movePiece(GameIdentity user, String matchId,
                        Integer fromX, Integer fromY, Integer toX, Integer toY) {
        if (fromX == null || fromY == null || toX == null || toY == null
                || !BlindXiangqiEngine.inBounds(fromX, fromY)
                || !BlindXiangqiEngine.inBounds(toX, toY)) {
            throw GameException.badRequest("GAME_MOVE_INVALID", "走子坐标不合法");
        }
        BlindRound state = requireBlindRound(user, matchId);
        Piece moving = BlindXiangqiEngine.pieceAt(state.pieces(), fromX, fromY);
        if (moving == null) {
            throw GameException.conflict("GAME_MOVE_EMPTY", "起点没有棋子");
        }
        BlindColor color = blindColor(state.round(), user.userId());
        if (!BlindXiangqiEngine.canMove(
                state.pieces(), color, fromX, fromY, toX, toY)) {
            throw GameException.badRequest("GAME_MOVE_INVALID", "该走法不合法");
        }
        Piece captured = BlindXiangqiEngine.pieceAt(state.pieces(), toX, toY);
        return applyBlindAction(
                state, user, "MOVE", moving, fromX, fromY, toX, toY, captured);
    }

    private MatchView applyBlindAction(BlindRound state, GameIdentity user, String action,
                                       Piece piece, Integer fromX, Integer fromY,
                                       int toX, int toY, Piece captured) {
        MatchRecord match = state.match();
        RoundRecord round = state.round();
        long now = System.currentTimeMillis();
        int nextMoveNo = round.moveCount() + 1;
        String opponent = opponentOf(match, user.userId());
        if (matches.updateTurnAndCount(
                round.id(), round.moveCount(), nextMoveNo, opponent,
                SqlTime.toDateTime(now)) == 0) {
            throw GameException.conflict("GAME_MOVE_CONFLICT", "操作冲突，请刷新棋盘");
        }

        boolean reveal = "REVEAL".equals(action);
        if (reveal) {
            if (blindPieces.reveal(round.id(), piece.id()) == 0) {
                throw GameException.conflict("GAME_MOVE_CONFLICT", "翻棋冲突，请刷新棋盘");
            }
        } else {
            if (blindPieces.move(round.id(), piece.id(), toX, toY) == 0) {
                throw GameException.conflict("GAME_MOVE_CONFLICT", "走子冲突，请刷新棋盘");
            }
            if (captured != null && blindPieces.capture(round.id(), captured.id()) == 0) {
                throw GameException.conflict("GAME_MOVE_CONFLICT", "吃子冲突，请刷新棋盘");
            }
        }

        matches.insertMove(MoveConverters.toEntity(round.id(), nextMoveNo, user.userId(),
                toX, toY, action, fromX, fromY, piece.type().name(), piece.color().name(),
                captured == null ? null : captured.type().name(),
                captured == null ? null : captured.color().name(), now));

        List<Piece> nextPieces = new ArrayList<>(state.pieces().size());
        for (Piece current : state.pieces()) {
            if (captured != null && current.id().equals(captured.id())) {
                continue;
            }
            if (current.id().equals(piece.id())) {
                nextPieces.add(reveal ? current.reveal() : current.moveTo(toX, toY));
            } else {
                nextPieces.add(current);
            }
        }

        String winner = null;
        if (captured != null && captured.type() == BlindPieceType.GENERAL) {
            // 将/帅先死的一方输，包括盲吃误伤己方将帅。
            winner = userIdOfColor(round, captured.color().opponent());
        }
        boolean draw = winner == null && BlindXiangqiEngine.generalsFacing(nextPieces);

        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "blind_action");
        frame.put("matchId", match.id());
        frame.put("roundId", round.id());
        frame.put("moveNo", nextMoveNo);
        frame.put("userId", user.userId());
        frame.put("action", action);
        frame.put("fromX", fromX);
        frame.put("fromY", fromY);
        frame.put("toX", toX);
        frame.put("toY", toY);
        frame.put("pieceType", piece.type().name());
        frame.put("pieceColor", piece.color().name());
        frame.put("capturedPieceType", captured == null ? null : captured.type().name());
        frame.put("capturedPieceColor", captured == null ? null : captured.color().name());
        frame.put("turnUserId", opponent);
        frame.put("moveCount", nextMoveNo);
        frame.put("result", winner != null ? "WIN" : draw ? "DRAW" : null);
        broadcaster.send(match, frame);

        if (winner != null) {
            settlement.finishGame(match, round, winner, "将帅被吃");
        } else if (draw) {
            settlement.finishGame(match, round, null, "将帅见面和棋");
        } else {
            broadcaster.broadcastMatch(match);
        }
        return assembler.detail(MatchGuards.requireMatch(matches, match.id()));
    }

    private TournamentRecord tournamentOf(MatchRecord match) {
        return Optional.ofNullable(tournaments.selectActiveById(match.tournamentId()))
                .map(TournamentConverters::toRecord)
                .orElse(null);
    }

    private void requireGomoku(MatchRecord match) {
        TournamentRecord tournament = tournamentOf(match);
        if (tournament != null && GameIds.BLIND_XIANGQI.equals(tournament.gameId())) {
            throw GameException.badRequest("GAME_ACTION_INVALID", "盲棋请使用翻棋或走子操作");
        }
    }

    private BlindRound requireBlindRound(GameIdentity user, String matchId) {
        MatchRecord match = MatchGuards.requireParticipant(matches, user, matchId);
        TournamentRecord tournament = tournamentOf(match);
        if (tournament == null || !GameIds.BLIND_XIANGQI.equals(tournament.gameId())) {
            throw GameException.badRequest("GAME_ACTION_INVALID", "当前比赛不是盲棋");
        }
        if (!"RUNNING".equals(match.status())) {
            throw GameException.conflict("GAME_MATCH_NOT_RUNNING", "比赛尚未开始");
        }
        RoundRecord round = Optional.ofNullable(matches.selectLatestRound(matchId))
                .map(MatchConverters::toRecord)
                .orElseThrow(() -> GameException.conflict("GAME_ROUND_MISSING", "对局尚未开始"));
        if (!"RUNNING".equals(round.status())) {
            throw GameException.conflict("GAME_ROUND_NOT_RUNNING", "当前小局不在对弈中");
        }
        if (!user.userId().equals(round.turnUserId())) {
            throw GameException.forbidden("还没轮到你操作");
        }
        return new BlindRound(match, round,
                BlindPieceConverters.toPieces(blindPieces.selectAlivePieces(round.id())));
    }

    /** 盲棋沿用 black_user_id 表示红方（先手），white_user_id 表示黑方。 */
    private static BlindColor blindColor(RoundRecord round, String userId) {
        return userId.equals(round.blackUserId()) ? BlindColor.RED : BlindColor.BLACK;
    }

    private static String userIdOfColor(RoundRecord round, BlindColor color) {
        return color == BlindColor.RED ? round.blackUserId() : round.whiteUserId();
    }

    private static String opponentOf(MatchRecord match, String userId) {
        return userId.equals(match.player1UserId())
                ? match.player2UserId() : match.player1UserId();
    }

    private record BlindRound(MatchRecord match, RoundRecord round, List<Piece> pieces) {
    }
}
