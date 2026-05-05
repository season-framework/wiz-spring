package com.wiz.socket;

import java.util.Map;

public class SocketBridge {

    private final SocketRoomRegistry rooms;
    private final SocketAuthHelper auth;

    public SocketBridge(SocketRoomRegistry rooms, SocketAuthHelper auth) {
        this.rooms = rooms;
        this.auth = auth;
    }

    public SocketEventResult handle(SocketSession session, String event, Map<String, Object> payload) {
        if (!auth.allowed(session.namespace(), event, payload)) {
            return new SocketEventResult(false, event, "unauthorized");
        }
        return switch (event) {
            case "connect" -> new SocketEventResult(true, event, "connected");
            case "join" -> join(session, payload);
            case "leave" -> leave(session, payload);
            case "disconnect" -> disconnect(session);
            default -> new SocketEventResult(false, event, "unknown event");
        };
    }

    private SocketEventResult join(SocketSession session, Map<String, Object> payload) {
        String room = room(payload);
        if (room == null) {
            return new SocketEventResult(false, "join", "missing room id");
        }
        rooms.join(session, room);
        return new SocketEventResult(true, "join", room);
    }

    private SocketEventResult leave(SocketSession session, Map<String, Object> payload) {
        String room = room(payload);
        if (room == null) {
            return new SocketEventResult(false, "leave", "missing room id");
        }
        rooms.leave(session, room);
        return new SocketEventResult(true, "leave", room);
    }

    private SocketEventResult disconnect(SocketSession session) {
        rooms.leaveAll(session);
        return new SocketEventResult(true, "disconnect", "disconnected");
    }

    private String room(Map<String, Object> payload) {
        Object value = payload.getOrDefault("namespace", payload.get("id"));
        return value == null || value.toString().isBlank() ? null : value.toString();
    }
}