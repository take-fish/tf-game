package com.fishmatrix.game.config;

import com.fishmatrix.game.auth.WsTicketHandshakeInterceptor;
import com.fishmatrix.game.ws.GameWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@RequiredArgsConstructor
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GameWebSocketHandler gameWebSocketHandler;
    private final WsTicketHandshakeInterceptor ticketInterceptor;
    private final GameProperties properties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gameWebSocketHandler, "/ws/game")
                .addInterceptors(ticketInterceptor)
                .setAllowedOriginPatterns(properties.originPatterns().toArray(String[]::new));
    }

    /**
     * 聊天消息为短文本；保留 1MB 缓冲兼容未来图片/复盘数据。
     */
    @Bean
    public ServletServerContainerFactoryBean gameWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(1024 * 1024);
        container.setMaxBinaryMessageBufferSize(1024 * 1024);
        return container;
    }
}
