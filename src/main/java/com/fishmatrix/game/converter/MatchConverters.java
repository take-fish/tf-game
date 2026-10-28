package com.fishmatrix.game.converter;

import com.fishmatrix.game.domain.MatchStage;
import com.fishmatrix.game.domain.MatchStatus;
import com.fishmatrix.game.domain.RoundStatus;
import com.fishmatrix.game.entity.GameMatchEntity;
import com.fishmatrix.game.entity.GameRoundEntity;
import com.fishmatrix.game.model.MatchRecord;
import com.fishmatrix.game.model.RoundRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * 比赛、小局与棋谱的实体 / 对外模型转换。
 *
 * <p>模型仍按字符串 stage/status 暴露（前端与既有服务在用），实体侧用枚举保证库里只写合法值；
 * 时间列统一经 {@link SqlTime} 换算，读写的毫秒口径与迁移前逐字节一致。</p>
 */
public final class MatchConverters {

    private MatchConverters() {
    }

    public static GameMatchEntity toEntity(MatchRecord match) {
        if (match == null) {
            return null;
        }
        GameMatchEntity entity = new GameMatchEntity();
        entity.setId(match.id());
        entity.setTournamentId(match.tournamentId());
        entity.setRoundNo(match.roundNo());
        entity.setSlotNo(match.slotNo());
        entity.setStage(stage(match.stage()));
        entity.setStatus(status(match.status()));
        entity.setPlayer1UserId(match.player1UserId());
        entity.setPlayer1Nickname(match.player1Nickname());
        entity.setPlayer2UserId(match.player2UserId());
        entity.setPlayer2Nickname(match.player2Nickname());
        entity.setPlayer1Score(match.player1Score());
        entity.setPlayer2Score(match.player2Score());
        entity.setWinnerUserId(match.winnerUserId());
        entity.setNextMatchId(match.nextMatchId());
        entity.setFirstGameId(match.firstGameId());
        entity.setGameChooserUserId(match.gameChooserUserId());
        entity.setCreatedAt(SqlTime.toDateTime(match.createdAt()));
        entity.setStartedAt(SqlTime.toDateTime(match.startedAt()));
        entity.setFinishedAt(SqlTime.toDateTime(match.finishedAt()));
        return entity;
    }

    public static MatchRecord toRecord(GameMatchEntity entity) {
        if (entity == null) {
            return null;
        }
        return new MatchRecord(
                entity.getId(),
                entity.getTournamentId(),
                intValue(entity.getRoundNo()),
                intValue(entity.getSlotNo()),
                name(entity.getStage()),
                name(entity.getStatus()),
                entity.getPlayer1UserId(),
                entity.getPlayer1Nickname(),
                entity.getPlayer2UserId(),
                entity.getPlayer2Nickname(),
                intValue(entity.getPlayer1Score()),
                intValue(entity.getPlayer2Score()),
                entity.getWinnerUserId(),
                entity.getNextMatchId(),
                SqlTime.toMillis(entity.getCreatedAt()),
                SqlTime.toNullableMillis(entity.getStartedAt()),
                SqlTime.toNullableMillis(entity.getFinishedAt()),
                entity.getFirstGameId(),
                entity.getGameChooserUserId());
    }

    public static List<MatchRecord> records(List<GameMatchEntity> entities) {
        List<MatchRecord> records = new ArrayList<>(entities.size());
        for (GameMatchEntity entity : entities) {
            records.add(toRecord(entity));
        }
        return records;
    }

    public static GameRoundEntity toEntity(RoundRecord round) {
        if (round == null) {
            return null;
        }
        GameRoundEntity entity = new GameRoundEntity();
        entity.setId(round.id());
        entity.setMatchId(round.matchId());
        entity.setGameId(round.gameId());
        entity.setGameNo(round.gameNo());
        entity.setStatus(roundStatus(round.status()));
        entity.setBlackUserId(round.blackUserId());
        entity.setWhiteUserId(round.whiteUserId());
        entity.setAttackerUserId(round.attackerUserId());
        entity.setTurnUserId(round.turnUserId());
        entity.setWinnerUserId(round.winnerUserId());
        entity.setMoveCount(round.moveCount());
        entity.setLastMoveAt(SqlTime.toDateTime(round.lastMoveAt()));
        entity.setRestUntil(SqlTime.toDateTime(round.restUntil()));
        entity.setReadyUserIds(round.readyUserIds());
        entity.setStateJson(round.stateJson());
        entity.setStateVersion(round.stateVersion());
        entity.setStartedAt(SqlTime.toDateTime(round.startedAt()));
        entity.setEndsAt(SqlTime.toDateTime(round.endsAt()));
        entity.setFinishedAt(SqlTime.toDateTime(round.finishedAt()));
        return entity;
    }

    public static RoundRecord toRecord(GameRoundEntity entity) {
        if (entity == null) {
            return null;
        }
        return new RoundRecord(
                entity.getId(),
                entity.getMatchId(),
                entity.getGameId(),
                intValue(entity.getGameNo()),
                name(entity.getStatus()),
                entity.getBlackUserId(),
                entity.getWhiteUserId(),
                entity.getAttackerUserId(),
                entity.getTurnUserId(),
                entity.getWinnerUserId(),
                intValue(entity.getMoveCount()),
                SqlTime.toMillis(entity.getLastMoveAt()),
                SqlTime.toNullableMillis(entity.getRestUntil()),
                entity.getReadyUserIds(),
                entity.getStateJson(),
                intValue(entity.getStateVersion()),
                SqlTime.toMillis(entity.getStartedAt()),
                SqlTime.toNullableMillis(entity.getEndsAt()),
                SqlTime.toNullableMillis(entity.getFinishedAt()));
    }

    public static List<RoundRecord> roundRecords(List<GameRoundEntity> entities) {
        List<RoundRecord> records = new ArrayList<>(entities.size());
        for (GameRoundEntity entity : entities) {
            records.add(toRecord(entity));
        }
        return records;
    }

    private static MatchStage stage(String value) {
        return value == null ? null : MatchStage.valueOf(value);
    }

    private static MatchStatus status(String value) {
        return value == null ? null : MatchStatus.valueOf(value);
    }

    private static RoundStatus roundStatus(String value) {
        return value == null ? null : RoundStatus.valueOf(value);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static int intValue(Integer value) {
        return value == null ? 0 : value;
    }
}
