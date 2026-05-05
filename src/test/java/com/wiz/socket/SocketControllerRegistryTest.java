package com.wiz.socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SocketControllerRegistryTest {

    @Test
    void dispatchesEventsToAppControllerMethods() {
        SocketNamespace namespace = new SocketNamespace("main", "page.xyz");
        SocketSession session = new SocketSession("sid-1", namespace);
        SocketNamespaceRegistry registry = new SocketNamespaceRegistry(List.of(new TestPageSocketController()), new SocketRoomRegistry());

        assertTrue(registry.dispatch(session, "connect", Map.of()).accepted());
        assertTrue(registry.dispatch(session, "join", Map.of("namespace", "room-1")).accepted());
        assertTrue(registry.rooms().contains(namespace, "room-1", "sid-1"));

        assertTrue(registry.dispatch(session, "leave", Map.of("id", "room-1")).accepted());
        assertFalse(registry.rooms().contains(namespace, "room-1", "sid-1"));
    }

    @Test
    void reportsUnknownControllerOrEvent() {
        SocketNamespaceRegistry registry = new SocketNamespaceRegistry(List.of(new TestPageSocketController()), new SocketRoomRegistry());

        assertFalse(registry.dispatch(new SocketSession("sid-1", new SocketNamespace("main", "missing")), "connect", Map.of()).accepted());
        assertFalse(registry.dispatch(new SocketSession("sid-1", new SocketNamespace("main", "page.xyz")), "custom", Map.of()).accepted());
    }

    @Test
    void emitsToExplicitRoomAndNamespaceRecipients() {
        SocketNamespace namespace = new SocketNamespace("main", "page.xyz");
        SocketNamespaceRegistry registry = new SocketNamespaceRegistry(List.of(new TestPageSocketController()), new SocketRoomRegistry());
        registry.dispatch(new SocketSession("sid-1", namespace), "join", Map.of("namespace", "room-1"));

        SocketOutboundEvent outbound = registry.emitToRoom(namespace, "room-1", "refresh", Map.of("ok", true));

        assertEquals(namespace, outbound.namespace());
        assertEquals("room-1", outbound.room());
        assertEquals("refresh", outbound.event());
        assertEquals(java.util.Set.of("sid-1"), outbound.recipients());
    }

    @Test
    void lifecycleExposesRegistryWithoutAutoStartingNetworkServer() {
        SocketNamespaceRegistry registry = new SocketNamespaceRegistry(List.of(new TestPageSocketController()), new SocketRoomRegistry());
        SocketServerLifecycle lifecycle = new SocketServerLifecycle(registry);

        assertFalse(lifecycle.isAutoStartup());
        assertFalse(lifecycle.isRunning());
        lifecycle.start();
        assertTrue(lifecycle.isRunning());
        assertSame(registry, lifecycle.registry());
        lifecycle.stop();
        assertFalse(lifecycle.isRunning());
    }
}
