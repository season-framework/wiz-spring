package com.wiz.socket;

import java.util.Map;

@FunctionalInterface
public interface SocketEventHandler {

    SocketEventResult handle(SocketSession session, Map<String, Object> payload, SocketRoomRegistry rooms);
}