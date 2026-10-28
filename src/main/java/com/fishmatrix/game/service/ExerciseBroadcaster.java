package com.fishmatrix.game.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.game.arena.ExerciseEngine;
import com.fishmatrix.game.ws.GameSession;
import com.fishmatrix.game.ws.GameSessionRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 巡检房间的视图与推送：参与者与观战者才能收到房间帧，且按接收者过滤可见状态。
 */
@RequiredArgsConstructor
@Component
class ExerciseBroadcaster {

    private final GameSessionRegistry registry;
    private final ExerciseEngine engine;
    private final ObjectMapper objectMapper;

    void broadcastState(ExerciseService.Room room) {
        if (room.state == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (GameSession session : registry.all()) {
            if (!visible(room, session)) {
                continue;
            }
            registry.send(session, stateFrame(room, session.getUserId(), now));
        }
        room.lastBroadcastAt = now;
    }

    /** 房间帧也按接收者过滤状态，避免开局瞬间把全图位置发给参与者。 */
    void broadcastRoom(ExerciseService.Room room) {
        for (GameSession session : registry.all()) {
            if (!visible(room, session)) {
                continue;
            }
            registry.send(session, Map.of(
                    "type", "exercise_room",
                    "room", roomView(room, session.getUserId())));
        }
    }

    void sendToRoom(ExerciseService.Room room, Object payload) {
        for (GameSession session : registry.all()) {
            if (visible(room, session)) {
                registry.send(session, payload);
            }
        }
    }

    Map<String, Object> roomView(ExerciseService.Room room, String viewerId) {
        List<Map<String, Object>> members = room.members.values().stream()
                .filter(member -> member.leftAt == 0)
                .map(member -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("userId", member.userId);
                    view.put("nickname", member.nickname);
                    view.put("team", member.team);
                    view.put("ready", member.ready);
                    view.put("bot", member.bot);
                    return view;
                })
                .toList();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("roomId", room.id);
        view.put("roomCode", room.roomCode);
        view.put("mode", room.mode);
        view.put("source", room.source);
        view.put("status", room.status);
        view.put("matchId", room.matchId);
        view.put("ownerUserId", room.ownerUserId);
        view.put("maxPlayers", room.maxPlayers);
        view.put("durationSeconds", room.durationSeconds);
        view.put("createdAt", room.createdAt);
        view.put("startedAt", room.startedAt == 0 ? null : room.startedAt);
        view.put("members", members);
        if (room.state != null) {
            Map<String, Object> state = objectMapper.convertValue(room.state, Map.class);
            if (viewerId != null && room.members.containsKey(viewerId)) {
                state.put("actors", engine.visibleActors(
                        room.state, viewerId, System.currentTimeMillis()));
            }
            view.put("state", state);
        }
        return view;
    }

    private Map<String, Object> stateFrame(ExerciseService.Room room, String viewerId, long now) {
        Map<String, Object> state = objectMapper.convertValue(room.state, Map.class);
        if (viewerId != null && room.members.containsKey(viewerId)) {
            state.put("actors", engine.visibleActors(room.state, viewerId, now));
            state.put("you", viewerId);
        } else {
            state.put("you", null);
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "exercise_state");
        frame.put("roomId", room.id);
        frame.put("roomCode", room.roomCode);
        frame.put("mode", room.mode);
        frame.put("matchId", room.matchId);
        frame.put("seq", room.state.seq);
        frame.put("serverTime", now);
        frame.put("you", viewerId);
        frame.put("state", state);
        return frame;
    }

    private static boolean visible(ExerciseService.Room room, GameSession session) {
        boolean participant = room.members.containsKey(session.getUserId());
        boolean watching = room.matchId != null
                && room.matchId.equals(session.getWatchingMatchId());
        return participant || watching;
    }
}
