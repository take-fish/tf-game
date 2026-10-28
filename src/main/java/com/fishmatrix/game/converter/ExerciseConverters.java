package com.fishmatrix.game.converter;

import com.fishmatrix.game.domain.ExerciseMode;
import com.fishmatrix.game.domain.ExerciseSource;
import com.fishmatrix.game.domain.ExerciseStatus;
import com.fishmatrix.game.entity.GameExerciseMemberEntity;
import com.fishmatrix.game.entity.GameExerciseResultEntity;
import com.fishmatrix.game.entity.GameExerciseRoomEntity;
import com.fishmatrix.game.model.ExerciseMemberRecord;
import com.fishmatrix.game.model.ExerciseResultRecord;
import com.fishmatrix.game.model.ExerciseRoomRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * 巡检房间、成员与战绩的实体 / 持久化行转换。
 *
 * <p>行对象的 mode/source/status 仍是字符串（服务层按常量判断），实体侧用枚举收敛非法值。</p>
 */
public final class ExerciseConverters {

    private ExerciseConverters() {
    }

    public static GameExerciseRoomEntity toEntity(ExerciseRoomRecord room) {
        if (room == null) {
            return null;
        }
        GameExerciseRoomEntity entity = new GameExerciseRoomEntity();
        entity.setId(room.id());
        entity.setRoomCode(room.roomCode());
        entity.setMode(room.mode() == null ? null : ExerciseMode.valueOf(room.mode()));
        entity.setSource(room.source() == null ? null : ExerciseSource.valueOf(room.source()));
        entity.setStatus(room.status() == null ? null : ExerciseStatus.valueOf(room.status()));
        entity.setMatchId(room.matchId());
        entity.setOwnerUserId(room.ownerUserId());
        entity.setMaxPlayers(room.maxPlayers());
        entity.setDurationSeconds(room.durationSeconds());
        entity.setStateJson(room.stateJson());
        entity.setStateVersion(room.stateVersion());
        entity.setWinnerUserId(room.winnerUserId());
        entity.setWinnerTeam(room.winnerTeam());
        entity.setFinishReason(room.finishReason());
        entity.setCreatedAt(SqlTime.toDateTime(room.createdAt()));
        entity.setStartedAt(SqlTime.toDateTime(room.startedAt()));
        entity.setFinishedAt(SqlTime.toDateTime(room.finishedAt()));
        return entity;
    }

    public static ExerciseRoomRecord toRecord(GameExerciseRoomEntity entity) {
        if (entity == null) {
            return null;
        }
        return new ExerciseRoomRecord(
                entity.getId(),
                entity.getRoomCode(),
                name(entity.getMode()),
                name(entity.getSource()),
                name(entity.getStatus()),
                entity.getMatchId(),
                entity.getOwnerUserId(),
                intValue(entity.getMaxPlayers()),
                intValue(entity.getDurationSeconds()),
                entity.getStateJson(),
                intValue(entity.getStateVersion()),
                entity.getWinnerUserId(),
                entity.getWinnerTeam(),
                entity.getFinishReason(),
                SqlTime.toMillis(entity.getCreatedAt()),
                SqlTime.toNullableMillis(entity.getStartedAt()),
                SqlTime.toNullableMillis(entity.getFinishedAt()));
    }

    public static List<ExerciseRoomRecord> roomRecords(List<GameExerciseRoomEntity> entities) {
        List<ExerciseRoomRecord> records = new ArrayList<>(entities.size());
        for (GameExerciseRoomEntity entity : entities) {
            records.add(toRecord(entity));
        }
        return records;
    }

    public static GameExerciseMemberEntity toEntity(ExerciseMemberRecord member) {
        if (member == null) {
            return null;
        }
        GameExerciseMemberEntity entity = new GameExerciseMemberEntity();
        entity.setRoomId(member.roomId());
        entity.setUserId(member.userId());
        entity.setNickname(member.nickname());
        entity.setTeam(member.team());
        entity.setReady(member.ready());
        entity.setBot(member.bot());
        entity.setScore(member.score());
        entity.setKills(member.kills());
        entity.setDeaths(member.deaths());
        entity.setAssists(member.assists());
        entity.setJoinedAt(SqlTime.toDateTime(member.joinedAt()));
        entity.setLeftAt(SqlTime.toDateTime(member.leftAt()));
        return entity;
    }

    public static ExerciseMemberRecord toRecord(GameExerciseMemberEntity entity) {
        if (entity == null) {
            return null;
        }
        return new ExerciseMemberRecord(
                entity.getRoomId(),
                entity.getUserId(),
                entity.getNickname(),
                entity.getTeam(),
                entity.isReady(),
                entity.isBot(),
                intValue(entity.getScore()),
                intValue(entity.getKills()),
                intValue(entity.getDeaths()),
                intValue(entity.getAssists()),
                SqlTime.toMillis(entity.getJoinedAt()),
                SqlTime.toNullableMillis(entity.getLeftAt()));
    }

    public static List<ExerciseMemberRecord> memberRecords(
            List<GameExerciseMemberEntity> entities) {
        List<ExerciseMemberRecord> records = new ArrayList<>(entities.size());
        for (GameExerciseMemberEntity entity : entities) {
            records.add(toRecord(entity));
        }
        return records;
    }

    public static GameExerciseResultEntity toEntity(ExerciseResultRecord result) {
        if (result == null) {
            return null;
        }
        GameExerciseResultEntity entity = new GameExerciseResultEntity();
        entity.setId(result.id());
        entity.setRoomId(result.roomId());
        entity.setMode(result.mode() == null ? null : ExerciseMode.valueOf(result.mode()));
        entity.setUserId(result.userId());
        entity.setNickname(result.nickname());
        entity.setTeam(result.team());
        entity.setRankNo(result.rankNo());
        entity.setScore(result.score());
        entity.setKills(result.kills());
        entity.setDeaths(result.deaths());
        entity.setAssists(result.assists());
        entity.setWinner(result.winner());
        entity.setCreatedAt(SqlTime.toDateTime(result.createdAt()));
        return entity;
    }

    public static ExerciseResultRecord toRecord(GameExerciseResultEntity entity) {
        if (entity == null) {
            return null;
        }
        return new ExerciseResultRecord(
                entity.getId(),
                entity.getRoomId(),
                name(entity.getMode()),
                entity.getUserId(),
                entity.getNickname(),
                entity.getTeam(),
                intValue(entity.getRankNo()),
                intValue(entity.getScore()),
                intValue(entity.getKills()),
                intValue(entity.getDeaths()),
                intValue(entity.getAssists()),
                entity.isWinner(),
                SqlTime.toMillis(entity.getCreatedAt()));
    }

    public static List<ExerciseResultRecord> resultRecords(
            List<GameExerciseResultEntity> entities) {
        List<ExerciseResultRecord> records = new ArrayList<>(entities.size());
        for (GameExerciseResultEntity entity : entities) {
            records.add(toRecord(entity));
        }
        return records;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static int intValue(Integer value) {
        return value == null ? 0 : value;
    }
}
