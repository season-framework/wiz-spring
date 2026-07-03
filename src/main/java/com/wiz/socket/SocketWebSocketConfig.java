package com.wiz.socket;

import java.util.Map;

import com.wiz.config.WizSocketProperties;

import jakarta.servlet.http.HttpSession;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.ServletWebSocketHandlerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

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
                .addInterceptors(new SocketHandshakeInterceptor())
                .setAllowedOrigins(socketProperties.getAllowedOrigins().toArray(String[]::new));
        if (registry instanceof ServletWebSocketHandlerRegistry servletRegistry) {
            servletRegistry.setOrder(Ordered.HIGHEST_PRECEDENCE);
        }
    }

    private static final class SocketHandshakeInterceptor implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, org.springframework.web.socket.WebSocketHandler wsHandler, Map<String, Object> attributes) {
            if (request instanceof ServletServerHttpRequest servletRequest) {
                HttpSession session = servletRequest.getServletRequest().getSession(false);
                if (session != null) {
                    attributes.put(SocketWebSocketHandler.HTTP_SESSION_ATTRIBUTE, session);
                }
                attributes.put(SocketWebSocketHandler.REMOTE_ADDRESS_ATTRIBUTE, servletRequest.getServletRequest().getRemoteAddr());
            }
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, org.springframework.web.socket.WebSocketHandler wsHandler, Exception exception) {
            // No-op.
        }
    }
}
