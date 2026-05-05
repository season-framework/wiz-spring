package com.wiz.socket;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, SocketSession> socketSessions = new ConcurrentHashMap<>();

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
        socketSession(session).ifPresent(socket -> {
            sessions.put(session.getId(), session);
            socketSessions.put(session.getId(), socket);
            sendResult(session, socket, dispatcher.dispatch(socket, "connect", Map.of()));
        });
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
            sendResult(session, socket.get(), dispatcher.dispatch(socket.get(), event, ProjectSocketDispatcher.payload(envelope.get("data"))));
        } catch (RuntimeException exception) {
            send(session, new SocketEventResult(false, "message", "invalid json message"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        SocketSession socket = socketSessions.remove(session.getId());
        sessions.remove(session.getId());
        if (socket == null) {
            socket = socketSession(session).orElse(null);
        }
        if (socket != null) {
            dispatcher.dispatch(socket, "disconnect", Map.of());
            dispatcher.rooms().leaveAll(socket);
        }
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

    private void sendResult(WebSocketSession session, SocketSession socket, SocketEventResult result) {
        if (result.room() == null || result.room().isBlank()) {
            send(session, result);
            return;
        }
        Set<String> recipients = dispatcher.rooms().members(socket.namespace(), result.room());
        if (recipients.isEmpty()) {
            send(session, result);
            return;
        }
        for (String recipient : recipients) {
            WebSocketSession target = sessions.get(recipient);
            if (target != null && target.isOpen()) {
                send(target, result);
            }
        }
    }

    private Map<String, Object> resultEnvelope(SocketEventResult result) {
        LinkedHashMap<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event", result.event());
        envelope.put("accepted", result.accepted());
        envelope.put("message", result.message());
        if (result.room() != null && !result.room().isBlank()) {
            envelope.put("room", result.room());
        }
        return envelope;
    }

    private String string(Object value, String defaultValue) {
        return value == null ? defaultValue : value.toString();
    }
}
