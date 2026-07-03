package com.wiz.socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SocketNamespaceTest {

    @Test
    void parsesSocketIoAndWebSocketNamespacePaths() {
        SocketNamespace socketIo = SocketNamespace.parse("/wiz/app/page.xyz").orElseThrow();
        SocketNamespace websocket = SocketNamespace.parse("/wiz/app/page.xyz").orElseThrow();

        assertEquals(new SocketNamespace("page.xyz"), socketIo);
        assertEquals(socketIo, websocket);
        assertEquals("/wiz/app/page.xyz", socketIo.socketIoPath());
        assertEquals("/wiz/app/page.xyz", socketIo.path());
    }

    @Test
    void parsesConfiguredNamespacePrefixes() {
        com.wiz.config.WizSocketProperties properties = new com.wiz.config.WizSocketProperties();
        properties.setPath("/rt/app");

        SocketNamespace socketIo = SocketNamespace.parse("/rt/app/page.xyz", properties).orElseThrow();
        SocketNamespace websocket = SocketNamespace.parse("/rt/app/page.xyz", properties).orElseThrow();

        assertEquals(socketIo, websocket);
        assertEquals("/rt/app/page.xyz", socketIo.socketIoPath(properties));
        assertEquals("/rt/app/page.xyz", socketIo.path(properties));
        assertTrue(SocketNamespace.parse("/wiz/app/page.xyz", properties).isEmpty());
    }

    @Test
    void rejectsInvalidNamespacePaths() {
        assertTrue(SocketNamespace.parse("/wiz/app/").isEmpty());
        assertTrue(SocketNamespace.parse("/wiz/app/main/page.xyz").isEmpty());
        assertTrue(SocketNamespace.parse("/other/main/page.xyz").isEmpty());
    }
}
