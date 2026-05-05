package com.wiz.socket;

import java.util.Optional;

public record SocketNamespace(String project, String appId) {

    public static Optional<SocketNamespace> parse(String path) {
        if (path == null) {
            return Optional.empty();
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        String prefix = normalized.startsWith("/wiz/ws/app/") ? "/wiz/ws/app/" : "/wiz/app/";
        if (!normalized.startsWith(prefix)) {
            return Optional.empty();
        }
        String[] parts = normalized.substring(prefix.length()).split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new SocketNamespace(parts[0], parts[1]));
    }

    public String socketIoPath() {
        return "/wiz/app/" + project + "/" + appId;
    }

    public String websocketPath() {
        return "/wiz/ws/app/" + project + "/" + appId;
    }
}