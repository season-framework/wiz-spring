package com.wiz.socket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class SocketBridgeTest {

    private final SocketNamespace namespace = new SocketNamespace("main", "page.xyz");

    @Test
    void handlesConnectJoinLeaveAndDisconnect() {
        SocketRoomRegistry rooms = new SocketRoomRegistry();
        SocketBridge bridge = new SocketBridge(rooms, (ns, event, payload) -> true);
        SocketSession session = new SocketSession("sid-1", namespace);

        assertTrue(bridge.handle(session, "connect", Map.of()).accepted());
        assertTrue(bridge.handle(session, "join", Map.of("id", "room-1")).accepted());
        assertTrue(rooms.contains(namespace, "room-1", "sid-1"));

        assertTrue(bridge.handle(session, "leave", Map.of("id", "room-1")).accepted());
        assertFalse(rooms.contains(namespace, "room-1", "sid-1"));

        bridge.handle(session, "join", Map.of("id", "room-2"));
        assertTrue(bridge.handle(session, "disconnect", Map.of()).accepted());
        assertFalse(rooms.contains(namespace, "room-2", "sid-1"));
    }

    @Test
    void rejectsMissingRoomOrUnauthorizedEvent() {
        SocketBridge rejected = new SocketBridge(new SocketRoomRegistry(), (ns, event, payload) -> false);
        SocketBridge accepted = new SocketBridge(new SocketRoomRegistry(), (ns, event, payload) -> true);
        SocketSession session = new SocketSession("sid-1", namespace);

        assertFalse(rejected.handle(session, "connect", Map.of()).accepted());
        assertFalse(accepted.handle(session, "join", Map.of()).accepted());
        assertFalse(accepted.handle(session, "custom", Map.of()).accepted());
    }
}