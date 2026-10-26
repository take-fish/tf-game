package com.fishmatrix.game.auth;

import com.fishmatrix.game.model.GameIdentity;
import com.fishmatrix.game.ws.GameWebSocketHandler;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class WsTicketHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WsTicketHandshakeInterceptor.class);

    private final WsTicketService tickets;
    private final ObjectProvider<Tracer> tracerProvider;

    public WsTicketHandshakeInterceptor(WsTicketService tickets, ObjectProvider<Tracer> tracerProvider) {
        this.tickets = tickets;
        this.tracerProvider = tracerProvider;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        String ticket = request.getURI().getQuery() == null
                ? null
                : UriComponentsBuilder.fromUri(request.getURI()).build()
                        .getQueryParams().getFirst("ticket");
        try {
            Optional<GameIdentity> identity = tickets.consume(ticket);
            if (identity.isEmpty()) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            attributes.put(GameWebSocketHandler.ATTR_IDENTITY, identity.get());
            Tracer tracer = tracerProvider.getIfAvailable();
            Span span = tracer == null ? null : tracer.currentSpan();
            if (span != null && span.context() != null) {
                attributes.put(GameWebSocketHandler.ATTR_TRACE_CONTEXT, span.context());
            }
            return true;
        } catch (Exception exception) {
            log.warn("比赛 WebSocket ticket 校验失败: {}", exception.getMessage());
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // no-op
    }
}
