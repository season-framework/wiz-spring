package com.wiz.socket;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class SocketWebSocketHandler extends TextWebSocketHandler {

    private final ProjectSocketDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    @Autowired
    public SocketWebSocketHandler(ProjectSocketDispatcher dispatcher) {
        this(dispatcher, new ObjectMapper());
    }

    SocketWebSocketHandler(ProjectSocketDispatcher dispatcher, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        socketSession(session).ifPresent(socket -> send(session, dispatcher.dispatch(socket, "connect", Map.of())));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Optional<SocketSession> socket = socketSession(session);
        if (socket.isEmpty()) {
            send(session, new SocketEventResult(false, "message", "invalid socket namespace"));
            return;
        }
        try {
            Map<String, Object> envelope = objectMapper.readValue(message.getPayload(), new TypeReference<>() {
            });
            String event = string(envelope.get("event"), "");
            if (event.isBlank()) {
                send(session, new SocketEventResult(false, "message", "missing event"));
                return;
            }
            send(session, dispatcher.dispatch(socket.get(), event, ProjectSocketDispatcher.payload(envelope.get("data"))));
        } catch (RuntimeException exception) {
            send(session, new SocketEventResult(false, "message", "invalid json message"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        socketSession(session).ifPresent(socket -> dispatcher.dispatch(socket, "disconnect", Map.of()));
    }

    private Optional<SocketSession> socketSession(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            return Optional.empty();
        }
        return SocketNamespace.parse(uri.getPath())
                .map(namespace -> new SocketSession(session.getId(), namespace));
    }

    private void send(WebSocketSession session, SocketEventResult result) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(resultEnvelope(result))));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to send socket event result", exception);
        }
    }

    private Map<String, Object> resultEnvelope(SocketEventResult result) {
        LinkedHashMap<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event", result.event());
        envelope.put("accepted", result.accepted());
        envelope.put("message", result.message());
        return envelope;
    }

    private String string(Object value, String defaultValue) {
        return value == null ? defaultValue : value.toString();
    }
}
