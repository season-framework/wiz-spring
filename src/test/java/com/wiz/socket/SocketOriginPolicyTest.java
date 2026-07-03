package com.wiz.socket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.wiz.config.WizSocketProperties;
import com.wiz.runtime.ProjectRuntimeCache;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.SockJsServiceRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class SocketOriginPolicyTest {

    @Test
    void webSocketConfigKeepsWildcardAllowedOriginsByDefault() {
        CapturingRegistry registry = new CapturingRegistry();

        new SocketWebSocketConfig(null, new WizSocketProperties()).registerWebSocketHandlers(registry);

        assertEquals(List.of("/wiz/app/{appId}"), registry.paths);
        assertArrayEquals(new String[] {"*"}, registry.allowedOrigins);
    }

    @Test
    void webSocketConfigRegistersConfiguredAllowedOrigins() {
        WizSocketProperties properties = new WizSocketProperties();
        properties.setAllowedOrigins(List.of("https://dev.example.com", "https://admin.example.com"));
        properties.setPath("/custom/app");
        CapturingRegistry registry = new CapturingRegistry();

        new SocketWebSocketConfig(null, properties).registerWebSocketHandlers(registry);

        assertEquals(List.of("/custom/app/{appId}"), registry.paths);
        assertArrayEquals(new String[] {"https://dev.example.com", "https://admin.example.com"}, registry.allowedOrigins);
    }

    @Test
    void socketIoPollingUsesSameAllowedOriginPolicy() {
        WizSocketProperties properties = new WizSocketProperties();
        properties.setAllowedOrigins(List.of("https://dev.example.com"));
        SocketIoHttpController controller = new SocketIoHttpController(null, new ObjectMapper(), properties);

        assertEquals(HttpStatus.FORBIDDEN, controller.poll(null, "https://blocked.example.com").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.receive("missing", "", "https://blocked.example.com").getStatusCode());
        assertEquals(HttpStatus.OK, controller.poll(null, "https://dev.example.com").getStatusCode());
        assertEquals(HttpStatus.OK, controller.poll(null, null).getStatusCode());
    }

    @Test
    void socketPropertiesAllowWildcardByDefault() {
        WizSocketProperties properties = new WizSocketProperties();

        assertTrue(properties.isOriginAllowed("https://any.example.com"));
    }

    @Test
    void socketIoPollingExpiresIdleSessionsByConfiguredTtl() {
        AtomicLong clock = new AtomicLong(0);
        WizSocketProperties properties = new WizSocketProperties();
        properties.setPollingSessionTtlMillis(10);
        SocketIoHttpController controller = new SocketIoHttpController(null, new ObjectMapper(), properties, clock::get);
        String sid = sid(controller.poll(null, null));

        assertEquals(1, controller.sessionCount());

        clock.set(11);
        ResponseEntity<String> expired = controller.poll(sid, null);

        assertEquals(HttpStatus.BAD_REQUEST, expired.getStatusCode());
        assertEquals(0, controller.sessionCount());
    }

    @Test
    void socketIoPollingRejectsNewSessionsOverConfiguredLimit() {
        WizSocketProperties properties = new WizSocketProperties();
        properties.setPollingSessionTtlMillis(0);
        properties.setMaxPollingSessions(1);
        SocketIoHttpController controller = new SocketIoHttpController(null, new ObjectMapper(), properties);

        assertEquals(HttpStatus.OK, controller.poll(null, null).getStatusCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, controller.poll(null, null).getStatusCode());
    }

    @Test
    void socketIoPollingQueueIsBoundedByConfiguredCapacity() {
        WizSocketProperties properties = new WizSocketProperties();
        properties.setPollingQueueCapacity(1);
        SocketIoHttpController controller = new SocketIoHttpController(new FakeSocketDispatcher(), new ObjectMapper(), properties);
        String sid = sid(controller.poll(null, null));

        assertEquals(HttpStatus.OK, controller.receive(sid, "40/wiz/app/page.chat,", null).getStatusCode());
        assertTrue(controller.poll(sid, null).getBody().contains("\"sid\""));
        assertEquals(HttpStatus.OK, controller.receive(sid, "42/wiz/app/page.chat,[\"send\",\"one\"]", null).getStatusCode());
        assertEquals(HttpStatus.OK, controller.receive(sid, "42/wiz/app/page.chat,[\"send\",\"two\"]", null).getStatusCode());

        assertEquals(1, controller.queueSize(sid));
        String payload = controller.poll(sid, null).getBody();
        assertFalse(payload.contains("send-1"));
        assertTrue(payload.contains("send-2"));
    }

    @Test
    void socketIoPollingUsesConfiguredNamespacePrefix() {
        WizSocketProperties properties = new WizSocketProperties();
        properties.setPath("/custom/app");
        SocketIoHttpController controller = new SocketIoHttpController(new FakeSocketDispatcher(), new ObjectMapper(), properties);
        String sid = sid(controller.poll(null, null));

        assertEquals(HttpStatus.OK, controller.receive(sid, "40/custom/app/page.chat,", null).getStatusCode());
        assertTrue(controller.poll(sid, null).getBody().contains("40/custom/app/page.chat"));
    }

    private String sid(ResponseEntity<String> response) {
        try {
            Map<String, Object> payload = new ObjectMapper().readValue(response.getBody().substring(1), new TypeReference<>() {
            });
            return payload.get("sid").toString();
        } catch (Exception exception) {
            throw new AssertionError("Failed to parse Socket.IO sid", exception);
        }
    }

    private static final class CapturingRegistry implements WebSocketHandlerRegistry, WebSocketHandlerRegistration {
        private List<String> paths = List.of();
        private String[] allowedOrigins = new String[0];

        @Override
        public WebSocketHandlerRegistration addHandler(WebSocketHandler handler, String... paths) {
            this.paths = List.of(paths);
            return this;
        }

        @Override
        public WebSocketHandlerRegistration setHandshakeHandler(HandshakeHandler handshakeHandler) {
            return this;
        }

        @Override
        public WebSocketHandlerRegistration addInterceptors(HandshakeInterceptor... interceptors) {
            return this;
        }

        @Override
        public WebSocketHandlerRegistration setAllowedOrigins(String... origins) {
            this.allowedOrigins = origins;
            return this;
        }

        @Override
        public WebSocketHandlerRegistration setAllowedOriginPatterns(String... originPatterns) {
            return this;
        }

        @Override
        public SockJsServiceRegistration withSockJS() {
            return null;
        }
    }

    private static final class FakeSocketDispatcher extends ProjectSocketDispatcher {

        private final AtomicInteger sequence = new AtomicInteger();

        private FakeSocketDispatcher() {
            super(null, new SocketRoomRegistry(), new ProjectRuntimeCache());
        }

        @Override
        public SocketEventResult dispatch(SocketSession session, String event, Map<String, Object> payload) {
            if ("connect".equals(event)) {
                return new SocketEventResult(true, event, "connected");
            }
            return new SocketEventResult(true, event, event + "-" + sequence.incrementAndGet());
        }
    }
}
