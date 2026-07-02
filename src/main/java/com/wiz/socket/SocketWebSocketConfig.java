package com.wiz.socket;

import com.wiz.config.WizSocketProperties;

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
    private final WizSocketProperties socketProperties;

    public SocketWebSocketConfig(SocketWebSocketHandler handler, WizSocketProperties socketProperties) {
        this.handler = handler;
        this.socketProperties = socketProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/wiz/ws/app/{project}/{appId}")
                .setAllowedOrigins(socketProperties.getAllowedOrigins().toArray(String[]::new));
        if (registry instanceof ServletWebSocketHandlerRegistry servletRegistry) {
            servletRegistry.setOrder(Ordered.HIGHEST_PRECEDENCE);
        }
    }
}
