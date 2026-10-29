package com.fishmatrix.game.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.game.arena.ArenaRoundFinishedEvent;
import com.fishmatrix.game.arena.ExerciseEngine;
import com.fishmatrix.game.arena.ExerciseEngine.ExercisePlayer;
import com.fishmatrix.game.arena.ExerciseEngine.ExerciseState;
import com.fishmatrix.game.arena.ExerciseEngine.PlayerSeed;
import com.fishmatrix.game.config.ExerciseProperties;
import com.fishmatrix.game.converter.ExerciseConverters;
import com.fishmatrix.game.converter.SqlTime;
import com.fishmatrix.game.mapper.GameExerciseMapper;
import com.fishmatrix.game.model.ExerciseResultRecord;
import com.fishmatrix.game.model.ExerciseRoomRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 巡检房间的生命周期：开局、结算与取消，含引擎状态初始化与结果落库。
 */
@Slf4j
@RequiredArgsConstructor
@Component
class ExerciseRoomLifecycle {

    private final ExerciseEngine engine;
    private final ExerciseProperties properties;
    private final GameExerciseMapper repository;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final ExerciseRoomRegistry rooms;
    private final ExerciseBroadcaster broadcaster;
    private final ExercisePersister persister;

    void start(ExerciseService.Room room, long now) {
        if (ExerciseEngine.MODE_TRAINING.equals(room.mode)) {
            for (int index = 1; index <= 3; index += 1) {
                ExerciseService.Member bot = new ExerciseService.Member(
                        "bot-" + room.id + "-" + index, "训练靶 " + index,
                        null, true, true, now);
                room.members.put(bot.userId, bot);
            }
        }
        List<PlayerSeed> seeds = new ArrayList<>();
        int index = 0;
        for (ExerciseService.Member member : room.members.values()) {
            if (member.leftAt != 0) {
                continue;
            }
            if (ExerciseEngine.MODE_TEAM.equals(room.mode) && !member.bot) {
                member.team = index % 2 == 0 ? "A" : "B";
            }
            seeds.add(new PlayerSeed(
                    member.userId, member.nickname, member.team, member.bot));
            index += 1;
        }
        room.state = engine.create(
                room.id, room.mode, seeds, now, now + room.durationSeconds * 1000L,
                Integer.toUnsignedLong(room.id.hashCode()));
        room.status = ExerciseService.STATUS_RUNNING;
        room.startedAt = now;
        room.lastBroadcastAt = 0;
        room.lastSnapshotAt = now;
        ExerciseRoomRecord row = ExercisePersister.row(
                room, persister.writeState(room.state));
        if (room.persisted) {
            repository.updateRoomState(
                    room.id, row.stateJson(), room.state.seq,
                    ExerciseService.STATUS_RUNNING);
            for (ExerciseService.Member member : room.members.values()) {
                if (member.bot && member.joinedAt == now) {
                    repository.insertMember(ExerciseConverters.toEntity(
                            ExercisePersister.memberRow(room.id, member)));
                }
            }
        } else {
            repository.insert(ExerciseConverters.toEntity(row));
            room.persisted = true;
            for (ExerciseService.Member member : room.members.values()) {
                repository.insertMember(ExerciseConverters.toEntity(
                        ExercisePersister.memberRow(room.id, member)));
            }
        }
        log.info("巡检房间开始 room={} mode={} players={}",
                room.id, room.mode, room.members.size());
        broadcaster.broadcastState(room);
        broadcaster.broadcastRoom(room);
    }

    void finish(ExerciseService.Room room, long now) {
        if (!ExerciseService.STATUS_RUNNING.equals(room.status) || room.state == null) {
            return;
        }
        room.status = ExerciseService.STATUS_FINISHED;
        ExerciseState state = room.state;
        String winner = state.winnerUserId;
        String winnerTeam = state.winnerTeam;
        String reason = state.finishReason == null ? "巡检结束" : state.finishReason;
        rooms.rooms.remove(room.id, room);
        try {
            List<Map<String, Object>> standings = standings(state);
            if (room.persisted) {
                repository.finishRoom(
                        room.id, winner, winnerTeam, reason,
                        persister.writeState(state), state.seq, SqlTime.toDateTime(now));
                for (ExercisePlayer actor : state.actors) {
                    if (actor.bot) {
                        continue;
                    }
                    repository.updateMemberStats(
                            room.id, actor.userId, actor.score,
                            actor.kills, actor.deaths, actor.assists);
                    repository.insertResult(ExerciseConverters.toEntity(
                            new ExerciseResultRecord(
                                    UUID.randomUUID().toString(), room.id, room.mode,
                                    actor.userId, actor.nickname, actor.team,
                                    rankOf(state, actor), actor.score, actor.kills,
                                    actor.deaths, actor.assists,
                                    actor.userId.equals(winner), now)));
                }
            }
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("type", "exercise_finished");
            frame.put("roomId", room.id);
            frame.put("roomCode", room.roomCode);
            frame.put("mode", room.mode);
            frame.put("matchId", room.matchId);
            frame.put("winnerUserId", winner);
            frame.put("winnerTeam", winnerTeam);
            frame.put("reason", reason);
            frame.put("standings", standings);
            frame.put("state", objectMapper.convertValue(state, Map.class));
            broadcaster.sendToRoom(room, frame);
            log.info("巡检房间结束 room={} winner={} team={} reason={}",
                    room.id, winner, winnerTeam, reason);
            if (ExerciseService.SOURCE_TOURNAMENT.equals(room.source) && room.matchId != null
                    && winner != null) {
                publisher.publishEvent(new ArenaRoundFinishedEvent(
                        room.matchId, room.id, winner, reason));
            }
        } catch (Exception exception) {
            rooms.rooms.put(room.id, room);
            room.status = ExerciseService.STATUS_RUNNING;
            log.error("巡检房间结算失败 room={}", room.id, exception);
        }
    }

    void cancel(ExerciseService.Room room, long now, String reason) {
        rooms.rooms.remove(room.id, room);
        if (room.persisted) {
            repository.cancelRoom(room.id, reason, SqlTime.toDateTime(now));
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "exercise_finished");
        frame.put("roomId", room.id);
        frame.put("roomCode", room.roomCode);
        frame.put("mode", room.mode);
        frame.put("matchId", room.matchId);
        frame.put("winnerUserId", null);
        frame.put("reason", reason);
        frame.put("standings", List.of());
        broadcaster.sendToRoom(room, frame);
    }

    static List<Map<String, Object>> standings(ExerciseState state) {
        List<ExercisePlayer> sorted = sortedActors(state);
        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = 1;
        for (ExercisePlayer actor : sorted) {
            if (actor.bot) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", rank);
            row.put("userId", actor.userId);
            row.put("nickname", actor.nickname);
            row.put("team", actor.team);
            row.put("score", actor.score);
            row.put("kills", actor.kills);
            row.put("deaths", actor.deaths);
            row.put("assists", actor.assists);
            row.put("winner", actor.userId.equals(state.winnerUserId));
            rows.add(row);
            rank += 1;
        }
        return rows;
    }

    static int rankOf(ExerciseState state, ExercisePlayer actor) {
        List<ExercisePlayer> humans = sortedActors(state).stream()
                .filter(entry -> !entry.bot)
                .toList();
        return humans.indexOf(actor) + 1;
    }

    private static List<ExercisePlayer> sortedActors(ExerciseState state) {
        List<ExercisePlayer> sorted = new ArrayList<>(state.actors);
        sorted.sort(Comparator
                .comparingInt((ExercisePlayer actor) -> actor.score).reversed()
                .thenComparing(Comparator.comparingInt(
                        (ExercisePlayer actor) -> actor.kills).reversed()));
        return sorted;
    }
}
