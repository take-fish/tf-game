package com.fishmatrix.game.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.common.trace.TraceIds;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.GameOnlineMember;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@RequiredArgsConstructor
@Component
public class GameSessionRegistry {

    private final Map<String, GameSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public GameSession register(WebSocketSession socket, GameIdentity identity) {
        GameSession session = new GameSession(socket, identity);
        sessions.put(socket.getId(), session);
        return session;
    }

    public GameSession get(String sessionId) {
        return sessions.get(sessionId);
    }

    public GameSession remove(String sessionId) {
        return sessions.remove(sessionId);
    }

    public List<GameSession> byUser(String userId) {
        return sessions.values().stream()
                .filter(session -> session.getUserId().equals(userId))
                .toList();
    }

    public List<GameSession> watching(String matchId) {
        return sessions.values().stream()
                .filter(session -> matchId != null && matchId.equals(session.getWatchingMatchId()))
                .toList();
    }

    public List<GameSession> all() {
        return List.copyOf(sessions.values());
    }

    public List<GameOnlineMember> onlineMembers() {
        Map<String, GameOnlineMember> members = new LinkedHashMap<>();
        for (GameSession session : sessions.values()) {
            members.putIfAbsent(
                    session.getUserId(),
                    new GameOnlineMember(session.getUserId(), session.getNickname()));
        }
        return List.copyOf(members.values());
    }

    public Set<String> onlineUserIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (GameSession session : sessions.values()) {
            ids.add(session.getUserId());
        }
        return ids;
    }

    public void send(GameSession session, Object payload) {
        sendRaw(session.getSocket(), payload);
    }

    public void sendRaw(WebSocketSession socket, Object payload) {
        if (socket == null || !socket.isOpen()) {
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(stampTrace(payload));
            synchronized (socket) {
                if (socket.isOpen()) {
                    socket.sendMessage(new TextMessage(body));
                }
            }
        } catch (JsonProcessingException exception) {
            log.warn("比赛 WebSocket 消息序列化失败", exception);
        } catch (IOException exception) {
            log.debug("比赛 WebSocket 发送失败（连接可能已关闭）: {}", exception.getMessage());
        }
    }

    public void broadcastAll(Object payload) {
        for (GameSession session : sessions.values()) {
            send(session, payload);
        }
    }

    public void broadcastUsers(Collection<String> userIds, Object payload) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        Set<String> targets = new LinkedHashSet<>(userIds);
        for (GameSession session : sessions.values()) {
            if (targets.contains(session.getUserId())) {
                send(session, payload);
            }
        }
    }

    /**
     * 比赛事件同时送达观战者和双方选手，按 session 去重，避免同一连接收到两次。
     */
    public void broadcastMatch(String matchId, Collection<String> participantIds, Object payload) {
        Set<String> ids = new LinkedHashSet<>();
        for (GameSession session : sessions.values()) {
            boolean watching = matchId != null && matchId.equals(session.getWatchingMatchId());
            boolean participant = participantIds != null && participantIds.contains(session.getUserId());
            if (watching || participant) {
                ids.add(session.getSocket().getId());
            }
        }
        for (String id : ids) {
            GameSession session = sessions.get(id);
            if (session != null) {
                send(session, payload);
            }
        }
    }

    private Object stampTrace(Object payload) {
        if (!(payload instanceof Map<?, ?> map) || map.containsKey("traceId")) {
            return payload;
        }
        String traceId = TraceIds.currentTraceId();
        if (traceId == null || traceId.isBlank()) {
            return payload;
        }
        Map<String, Object> stamped = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            stamped.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        stamped.put("traceId", traceId);
        return stamped;
    }
}
