package com.wiz.socket;

import java.util.Map;

import jakarta.servlet.http.HttpSession;

public record SocketSession(String id, SocketNamespace namespace, Map<String, String> cookies, HttpSession httpSession, String remoteAddress) {

    public SocketSession(String id, SocketNamespace namespace) {
        this(id, namespace, Map.of(), null, "");
    }

    public SocketSession {
        cookies = cookies == null ? Map.of() : Map.copyOf(cookies);
        remoteAddress = remoteAddress == null ? "" : remoteAddress;
    }
}
