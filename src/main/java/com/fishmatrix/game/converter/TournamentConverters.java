package com.fishmatrix.game.converter;

import com.fishmatrix.game.domain.BracketType;
import com.fishmatrix.game.domain.TournamentFormat;
import com.fishmatrix.game.domain.TournamentKind;
import com.fishmatrix.game.domain.TournamentPlayerStatus;
import com.fishmatrix.game.domain.TournamentStatus;
import com.fishmatrix.game.entity.GameTournamentEntity;
import com.fishmatrix.game.entity.GameTournamentPlayerEntity;
import com.fishmatrix.game.model.TournamentPlayerRecord;
import com.fishmatrix.game.model.TournamentRecord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 赛事与赛事选手的实体 / 对外模型转换。
 *
 * <p>随机剧本池在库里是逗号分隔字符串，固定剧本赛事为 NULL；这里保持迁移前的拆分口径。</p>
 */
public final class TournamentConverters {

    private TournamentConverters() {
    }

    public static GameTournamentEntity toEntity(TournamentRecord tournament) {
        if (tournament == null) {
            return null;
        }
        GameTournamentEntity entity = new GameTournamentEntity();
        entity.setId(tournament.id());
        entity.setName(tournament.name());
        entity.setGameId(tournament.gameId());
        entity.setGamePool(poolCsv(tournament.gamePool()));
        entity.setBlindLayout(tournament.blindLayout());
        entity.setKind(tournament.kind() == null ? null : TournamentKind.valueOf(tournament.kind()));
        entity.setFormat(
                tournament.format() == null ? null : TournamentFormat.valueOf(tournament.format()));
        entity.setBracketType(tournament.bracketType() == null
                ? null : BracketType.valueOf(tournament.bracketType()));
        entity.setStatus(tournament.status() == null
                ? null : TournamentStatus.valueOf(tournament.status()));
        entity.setCreatorUserId(tournament.creatorUserId());
        entity.setCreatorNickname(tournament.creatorNickname());
        entity.setTargetPlayers(tournament.targetPlayers());
        entity.setInviteDeadline(SqlTime.toDateTime(tournament.inviteDeadline()));
        entity.setCurrentRound(tournament.currentRound());
        entity.setTotalRounds(tournament.totalRounds());
        entity.setChampionUserId(tournament.championUserId());
        entity.setChampionNickname(tournament.championNickname());
        entity.setRunnerUpUserId(tournament.runnerUpUserId());
        entity.setRunnerUpNickname(tournament.runnerUpNickname());
        entity.setCreatedAt(SqlTime.toDateTime(tournament.createdAt()));
        entity.setStartedAt(SqlTime.toDateTime(tournament.startedAt()));
        entity.setFinishedAt(SqlTime.toDateTime(tournament.finishedAt()));
        return entity;
    }

    public static TournamentRecord toRecord(GameTournamentEntity entity) {
        if (entity == null) {
            return null;
        }
        return new TournamentRecord(
                entity.getId(),
                entity.getName(),
                entity.getGameId(),
                name(entity.getKind()),
                name(entity.getFormat()),
                name(entity.getBracketType()),
                name(entity.getStatus()),
                entity.getCreatorUserId(),
                entity.getCreatorNickname(),
                intValue(entity.getTargetPlayers()),
                SqlTime.toMillis(entity.getInviteDeadline()),
                intValue(entity.getCurrentRound()),
                intValue(entity.getTotalRounds()),
                entity.getChampionUserId(),
                entity.getChampionNickname(),
                entity.getRunnerUpUserId(),
                entity.getRunnerUpNickname(),
                SqlTime.toMillis(entity.getCreatedAt()),
                SqlTime.toNullableMillis(entity.getStartedAt()),
                SqlTime.toNullableMillis(entity.getFinishedAt()),
                entity.getBlindLayout(),
                readPool(entity.getGamePool()));
    }

    public static List<TournamentRecord> records(List<GameTournamentEntity> entities) {
        List<TournamentRecord> records = new ArrayList<>(entities.size());
        for (GameTournamentEntity entity : entities) {
            records.add(toRecord(entity));
        }
        return records;
    }

    public static GameTournamentPlayerEntity toEntity(TournamentPlayerRecord player) {
        if (player == null) {
            return null;
        }
        GameTournamentPlayerEntity entity = new GameTournamentPlayerEntity();
        entity.setTournamentId(player.tournamentId());
        entity.setUserId(player.userId());
        entity.setNickname(player.nickname());
        entity.setSeed(player.seed());
        entity.setStatus(player.status() == null
                ? null : TournamentPlayerStatus.valueOf(player.status()));
        entity.setInvitedAt(SqlTime.toDateTime(player.invitedAt()));
        entity.setRespondedAt(SqlTime.toDateTime(player.respondedAt()));
        return entity;
    }

    public static TournamentPlayerRecord toRecord(GameTournamentPlayerEntity entity) {
        if (entity == null) {
            return null;
        }
        return new TournamentPlayerRecord(
                entity.getTournamentId(),
                entity.getUserId(),
                entity.getNickname(),
                intValue(entity.getSeed()),
                name(entity.getStatus()),
                SqlTime.toMillis(entity.getInvitedAt()),
                SqlTime.toNullableMillis(entity.getRespondedAt()));
    }

    public static List<TournamentPlayerRecord> playerRecords(
            List<GameTournamentPlayerEntity> entities) {
        List<TournamentPlayerRecord> records = new ArrayList<>(entities.size());
        for (GameTournamentPlayerEntity entity : entities) {
            records.add(toRecord(entity));
        }
        return records;
    }

    public static List<GameTournamentPlayerEntity> playerEntities(
            List<TournamentPlayerRecord> players) {
        List<GameTournamentPlayerEntity> entities = new ArrayList<>(players.size());
        for (TournamentPlayerRecord player : players) {
            entities.add(toEntity(player));
        }
        return entities;
    }

    /** 随机池按逗号分隔落库；固定剧本赛事保持 NULL。 */
    private static String poolCsv(List<String> pool) {
        return pool == null || pool.isEmpty() ? null : String.join(",", pool);
    }

    private static List<String> readPool(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::strip)
                .filter(entry -> !entry.isEmpty())
                .toList();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static int intValue(Integer value) {
        return value == null ? 0 : value;
    }
}
