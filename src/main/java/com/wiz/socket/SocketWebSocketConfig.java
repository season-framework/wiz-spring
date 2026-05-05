package com.wiz.socket;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.ServletWebSocketHandlerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class SocketWebSocketConfig implements WebSocketConfigurer {

    private final SocketWebSocketHandler handler;

    public SocketWebSocketConfig(SocketWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/wiz/ws/app/{project}/{appId}")
                .setAllowedOrigins("*");
        if (registry instanceof ServletWebSocketHandlerRegistry servletRegistry) {
            servletRegistry.setOrder(Ordered.HIGHEST_PRECEDENCE);
        }
    }
}
