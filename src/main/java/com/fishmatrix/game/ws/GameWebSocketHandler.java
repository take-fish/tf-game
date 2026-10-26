package com.fishmatrix.game.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishmatrix.common.error.ApiException;
import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.model.MatchView;
import com.fishmatrix.game.model.SendChatRequest;
import com.fishmatrix.game.model.WsInbound;
import com.fishmatrix.game.service.GameChatService;
import com.fishmatrix.game.service.MatchService;
import com.fishmatrix.game.service.TournamentService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 比赛 WebSocket：大厅、观战订阅、准备/落子/认输、公开与选手聊天。
 * 身份来自一次性 ticket 的握手属性，不信任客户端自报。
 */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    public static final String ATTR_IDENTITY = "game.identity";
    public static final String ATTR_TRACE_CONTEXT = "game.traceContext";

    private static final Logger log = LoggerFactory.getLogger(GameWebSocketHandler.class);

    private final TournamentService tournaments;
    private final MatchService matches;
    private final GameChatService chats;
    private final GameSessionRegistry registry;
    private final ObjectMapper objectMapper;

    public GameWebSocketHandler(
            TournamentService tournaments,
            MatchService matches,
            GameChatService chats,
            GameSessionRegistry registry,
            ObjectMapper objectMapper) {
        this.tournaments = tournaments;
        this.matches = matches;
        this.chats = chats;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) {
        GameIdentity identity = (GameIdentity) socket.getAttributes().get(ATTR_IDENTITY);
        if (identity == null) {
            try {
                socket.close(CloseStatus.NOT_ACCEPTABLE);
            } catch (Exception ignored) {
                // ignore
            }
            return;
        }
        GameSession session = registry.register(socket, identity);
        matches.onConnect(identity.userId());
        sendLobbyState(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        GameSession session = registry.get(socket.getId());
        if (session == null) {
            return;
        }
        WsInbound inbound;
        try {
            inbound = objectMapper.readValue(message.getPayload(), WsInbound.class);
        } catch (Exception exception) {
            sendError(session, "GAME_FRAME_INVALID", "消息格式不正确");
            return;
        }
        String type = inbound.type() == null ? "" : inbound.type();
        try {
            switch (type) {
                case "ping" -> registry.send(session, Map.of(
                        "type", "pong",
                        "serverTime", System.currentTimeMillis()));
                case "refresh_lobby" -> sendLobbyState(session);
                case "watch_match" -> watchMatch(session, inbound.matchId());
                case "leave_match" -> {
                    session.setWatchingMatchId(null);
                    registry.send(session, Map.of("type", "left_match"));
                }
                case "match_ready" -> registry.send(session, Map.of(
                        "type", "match_update",
                        "match", matches.ready(session.getIdentity(), requireMatchId(inbound))));
                case "place_stone" -> registry.send(session, Map.of(
                        "type", "match_update",
                        "match", matches.placeStone(
                                session.getIdentity(), requireMatchId(inbound),
                                inbound.x(), inbound.y())));
                case "resign" -> registry.send(session, Map.of(
                        "type", "match_update",
                        "match", matches.resign(session.getIdentity(), requireMatchId(inbound))));
                case "chat" -> chats.send(session.getIdentity(), new SendChatRequest(
                        inbound.tournamentId(), inbound.matchId(), inbound.channel(),
                        inbound.toUserId(), inbound.content()));
                default -> sendError(session, "GAME_FRAME_UNKNOWN", "未知消息类型");
            }
        } catch (ApiException exception) {
            sendError(session, exception.code(), exception.getMessage());
        } catch (Exception exception) {
            log.warn("处理比赛 WebSocket 帧失败 type={} user={}",
                    type, session.getUserId(), exception);
            sendError(session, "GAME_INTERNAL", "比赛服务处理失败");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        GameSession removed = registry.remove(socket.getId());
        if (removed == null) {
            return;
        }
        if (registry.byUser(removed.getUserId()).isEmpty()) {
            matches.onDisconnect(removed.getUserId());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession socket, Throwable exception) {
        log.debug("比赛 WebSocket 传输错误 session={}: {}", socket.getId(), exception.getMessage());
        try {
            socket.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ignored) {
            // ignore
        }
    }

    /** 先校验再订阅：约战校验失败时不能留下观战关系，否则会继续收到比赛广播。 */
    void watchMatch(GameSession session, String matchId) {
        String id = requireMatchId(matchId);
        MatchView match = matches.detailForUser(session.getIdentity(), id);
        session.setWatchingMatchId(id);
        registry.send(session, Map.of(
                "type", "match_update",
                "match", match));
    }

    private void sendLobbyState(GameSession session) {
        GameIdentity identity = session.getIdentity();
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "game_ready");
        frame.put("user", Map.of(
                "userId", identity.userId(),
                "nickname", identity.nickname()));
        frame.put("onlineUserIds", registry.onlineUserIds());
        frame.put("tournaments", tournaments.list(20));
        frame.put("invitations", tournaments.invitations(identity.userId()));
        frame.put("duels", tournaments.listDuelsForUser(identity.userId(), 20));
        frame.put("activeMatches", matches.activeForUser(identity.userId()));
        registry.send(session, frame);
    }

    private static String requireMatchId(WsInbound inbound) {
        if (inbound.matchId() == null || inbound.matchId().isBlank()) {
            throw ApiException.badRequest("GAME_FRAME_INVALID", "缺少比赛 ID");
        }
        return inbound.matchId();
    }

    private static String requireMatchId(String matchId) {
        if (matchId == null || matchId.isBlank()) {
            throw ApiException.badRequest("GAME_FRAME_INVALID", "缺少比赛 ID");
        }
        return matchId;
    }

    private void sendError(GameSession session, String code, String message) {
        registry.send(session, Map.of(
                "type", "error",
                "code", code,
                "message", message == null ? "比赛操作失败" : message));
    }
}
