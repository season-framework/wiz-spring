package com.wiz.socket;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SocketNamespaceRegistry {

    private final Map<String, SocketController> controllers = new LinkedHashMap<>();
    private final SocketRoomRegistry rooms;

    @Autowired
    public SocketNamespaceRegistry(List<SocketController> controllers, SocketRoomRegistry rooms) {
        controllers.forEach(controller -> this.controllers.put(controller.appId(), controller));
        this.rooms = rooms;
    }

    public SocketNamespaceRegistry(Iterable<SocketController> controllers, SocketRoomRegistry rooms) {
        controllers.forEach(controller -> this.controllers.put(controller.appId(), controller));
        this.rooms = rooms;
    }

    public Optional<SocketController> controller(SocketNamespace namespace) {
        return Optional.ofNullable(controllers.get(namespace.appId()));
    }

    public SocketEventResult dispatch(SocketSession session, String event, Map<String, Object> payload) {
        return controller(session.namespace())
                .map(controller -> invoke(controller, session, event, payload))
                .orElseGet(() -> new SocketEventResult(false, event, "socket controller not found"));
    }

    public SocketOutboundEvent emitToRoom(SocketNamespace namespace, String room, String event, Object payload) {
        return new SocketOutboundEvent(namespace, room, event, payload, rooms.members(namespace, room));
    }

    public SocketRoomRegistry rooms() {
        return rooms;
    }

    private SocketEventResult invoke(SocketController controller, SocketSession session, String event, Map<String, Object> payload) {
        SocketEventHandler handler = controller.handlers().get(event);
        if (handler == null) {
            return new SocketEventResult(false, event, "socket event handler not found");
        }
        return handler.handle(session, payload, rooms);
    }
}