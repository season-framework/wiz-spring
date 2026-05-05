package com.wiz.socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SocketNamespaceTest {

    @Test
    void parsesSocketIoAndWebSocketNamespacePaths() {
        SocketNamespace socketIo = SocketNamespace.parse("/wiz/app/main/page.xyz").orElseThrow();
        SocketNamespace websocket = SocketNamespace.parse("/wiz/ws/app/main/page.xyz").orElseThrow();

        assertEquals(new SocketNamespace("main", "page.xyz"), socketIo);
        assertEquals(socketIo, websocket);
        assertEquals("/wiz/app/main/page.xyz", socketIo.socketIoPath());
        assertEquals("/wiz/ws/app/main/page.xyz", socketIo.websocketPath());
    }

    @Test
    void rejectsInvalidNamespacePaths() {
        assertTrue(SocketNamespace.parse("/wiz/app/main").isEmpty());
        assertTrue(SocketNamespace.parse("/other/main/page.xyz").isEmpty());
    }
}