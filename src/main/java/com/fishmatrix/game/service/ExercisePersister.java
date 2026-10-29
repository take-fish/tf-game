package com.fishmatrix.game.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.game.arena.ExerciseEngine.ExerciseState;
import com.fishmatrix.game.mapper.GameExerciseMapper;
import com.fishmatrix.game.model.ExerciseMemberRecord;
import com.fishmatrix.game.model.ExerciseRoomRecord;
import com.fishmatrix.game.model.GameException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 巡检房间的持久化：房间/成员行组装、状态快照与序列化。
 */
@Slf4j
@RequiredArgsConstructor
@Component
class ExercisePersister {

    private final GameExerciseMapper repository;
    private final ObjectMapper objectMapper;

    ExerciseState readState(String json) throws java.io.IOException {
        return objectMapper.readValue(json, ExerciseState.class);
    }

    String writeState(ExerciseState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception exception) {
            throw GameException.unavailable("巡检状态序列化失败");
        }
    }

    void persistState(ExerciseService.Room room) {
        if (!room.persisted || room.state == null) {
            return;
        }
        try {
            repository.updateRoomState(
                    room.id, writeState(room.state), room.state.seq,
                    ExerciseService.STATUS_RUNNING);
            room.lastSnapshotAt = System.currentTimeMillis();
        } catch (Exception exception) {
            log.warn("巡检快照写入失败 room={}", room.id, exception);
        }
    }

    static ExerciseRoomRecord row(ExerciseService.Room room, String stateJson) {
        return new ExerciseRoomRecord(
                room.id, room.roomCode, room.mode, room.source, room.status,
                room.matchId, room.ownerUserId, room.maxPlayers, room.durationSeconds,
                stateJson, room.state == null ? 0 : room.state.seq,
                null, null, null, room.createdAt,
                room.startedAt == 0 ? null : room.startedAt, null);
    }

    static ExerciseMemberRecord memberRow(String roomId, ExerciseService.Member member) {
        return new ExerciseMemberRecord(
                roomId, member.userId, member.nickname, member.team,
                member.ready, member.bot, 0, 0, 0, 0,
                member.joinedAt, member.leftAt == 0 ? null : member.leftAt);
    }
}
