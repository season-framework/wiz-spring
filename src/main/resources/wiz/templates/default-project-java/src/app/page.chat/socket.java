import java.time.Instant;
import java.util.Map;

import com.wiz.socket.SocketController;
import com.wiz.socket.SocketEventHandler;
import com.wiz.socket.SocketEventResult;
import com.wiz.socket.SocketRoomRegistry;
import com.wiz.socket.SocketSession;

public final class PageChatSocketController implements SocketController {

    private static final String DEFAULT_ROOM = "lobby";

    public String appId() {
        return "page.chat";
    }

    public Map<String, SocketEventHandler> handlers() {
        return Map.of(
                "connect", this::connect,
                "join", this::join,
                "send", this::send,
                "disconnect", this::disconnect);
    }

    private SocketEventResult connect(SocketSession session, Map<String, Object> payload, SocketRoomRegistry rooms) {
        return new SocketEventResult(true, "connect", "connected");
    }

    private SocketEventResult join(SocketSession session, Map<String, Object> payload, SocketRoomRegistry rooms) {
        String room = room(payload);
        rooms.join(session, room);
        return new SocketEventResult(true, "join", room);
    }

    private SocketEventResult send(SocketSession session, Map<String, Object> payload, SocketRoomRegistry rooms) {
        String room = room(payload);
        if (!rooms.contains(session.namespace(), room, session.id())) {
            rooms.join(session, room);
        }
        String text = value(payload, "text", "").trim();
        if (text.isBlank()) {
            return new SocketEventResult(false, "send", "empty message");
        }
        if (text.length() > 500) {
            text = text.substring(0, 500);
        }
        String name = value(payload, "name", "Guest").trim();
        if (name.isBlank() || "Guest".equalsIgnoreCase(name)) {
            name = guestName(session);
        }
        String message = "{"
                + "\"name\":\"" + escape(name) + "\","
                + "\"text\":\"" + escape(text) + "\","
                + "\"sentAt\":\"" + Instant.now() + "\""
                + "}";
        return new SocketEventResult(true, "chat.message", message, room);
    }

    private SocketEventResult disconnect(SocketSession session, Map<String, Object> payload, SocketRoomRegistry rooms) {
        rooms.leaveAll(session);
        return new SocketEventResult(true, "disconnect", "disconnected");
    }

    private String room(Map<String, Object> payload) {
        String room = value(payload, "room", DEFAULT_ROOM).trim();
        return room.isBlank() ? DEFAULT_ROOM : room;
    }

    private String value(Map<String, Object> payload, String key, String defaultValue) {
        Object value = payload.get(key);
        return value == null ? defaultValue : value.toString();
    }

    private String guestName(SocketSession session) {
        String id = session.id() == null ? "" : session.id().replace("-", "");
        if (id.length() > 6) {
            id = id.substring(0, 6);
        }
        return id.isBlank() ? "Guest" : "Guest-" + id;
    }

    private String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
