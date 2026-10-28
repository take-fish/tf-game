package com.fishmatrix.game.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * 巡检房间的进程内注册表：单实例内存房间，重启后由持久化快照恢复。
 */
@Component
class ExerciseRoomRegistry {

    final Map<String, ExerciseService.Room> rooms = new ConcurrentHashMap<>();
    final AtomicBoolean recovered = new AtomicBoolean(false);
    volatile long lastSweepAt;

    ExerciseService.Room find(String key) {
        ExerciseService.Room byId = rooms.get(key);
        if (byId != null) {
            return byId;
        }
        return rooms.values().stream()
                .filter(room -> key.equalsIgnoreCase(room.roomCode))
                .findFirst().orElse(null);
    }

    ExerciseService.Room require(String key) {
        if (key == null || key.isBlank()) {
            throw com.fishmatrix.game.model.GameException.badRequest(
                    "GAME_FRAME_INVALID", "缺少房间 ID");
        }
        ExerciseService.Room room = find(key);
        if (room == null) {
            throw com.fishmatrix.game.model.GameException.notFound("巡检房间不存在");
        }
        return room;
    }

    /** 用户当前所在的可加入/进行中的休闲房间。 */
    String activeCasualRoomOf(String userId) {
        return rooms.values().stream()
                .filter(room -> ExerciseService.SOURCE_CASUAL.equals(room.source))
                .filter(room -> ExerciseService.STATUS_WAITING.equals(room.status)
                        || ExerciseService.STATUS_RUNNING.equals(room.status))
                .filter(room -> room.members.containsKey(userId))
                .map(room -> room.id)
                .findFirst().orElse(null);
    }
}
