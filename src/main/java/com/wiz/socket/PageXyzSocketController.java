package com.wiz.socket;

import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class PageXyzSocketController implements SocketController {

    @Override
    public String appId() {
        return "page.xyz";
    }

    @Override
    public Map<String, SocketEventHandler> handlers() {
        return Map.of(
                "connect", this::connect,
                "join", this::join,
                "leave", this::leave,
                "disconnect", this::disconnect);
    }

    private SocketEventResult connect(SocketSession session, Map<String, Object> payload, SocketRoomRegistry rooms) {
        return new SocketEventResult(true, "connect", "connected");
    }

    private SocketEventResult join(SocketSession session, Map<String, Object> payload, SocketRoomRegistry rooms) {
        String room = room(payload);
        if (room == null) {
            return new SocketEventResult(false, "join", "missing room id");
        }
        rooms.join(session, room);
        return new SocketEventResult(true, "join", room);
    }

    private SocketEventResult leave(SocketSession session, Map<String, Object> payload, SocketRoomRegistry rooms) {
        String room = room(payload);
        if (room == null) {
            return new SocketEventResult(false, "leave", "missing room id");
        }
        rooms.leave(session, room);
        return new SocketEventResult(true, "leave", room);
    }

    private SocketEventResult disconnect(SocketSession session, Map<String, Object> payload, SocketRoomRegistry rooms) {
        rooms.leaveAll(session);
        return new SocketEventResult(true, "disconnect", "disconnected");
    }

    private String room(Map<String, Object> payload) {
        Object value = payload.getOrDefault("namespace", payload.get("id"));
        return value == null || value.toString().isBlank() ? null : value.toString();
    }
}