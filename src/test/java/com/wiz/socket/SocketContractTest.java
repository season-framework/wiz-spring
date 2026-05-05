package com.wiz.socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SocketContractTest {

    @Test
    void baselinePageXyzSocketContractWorksWithStandardEventMessages() {
        SocketNamespace namespace = SocketNamespace.parse("/wiz/ws/app/main/page.xyz").orElseThrow();
        SocketSession session = new SocketSession("sid-1", namespace);
        SocketNamespaceRegistry registry = new SocketNamespaceRegistry(List.of(new PageXyzSocketController()), new SocketRoomRegistry());

        assertEquals("/wiz/app/main/page.xyz", namespace.socketIoPath());
        assertTrue(registry.dispatch(session, "connect", Map.of()).accepted());
        assertTrue(registry.dispatch(session, "join", Map.of("id", "room-1")).accepted());
        assertTrue(registry.rooms().contains(namespace, "room-1", "sid-1"));
        assertTrue(registry.dispatch(session, "disconnect", Map.of()).accepted());
    }
}