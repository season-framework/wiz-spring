package com.wiz.socket;

import java.util.Optional;

import com.wiz.config.WizSocketProperties;

public record SocketNamespace(String appId) {

    public static Optional<SocketNamespace> parse(String path) {
        return parse(path, new WizSocketProperties());
    }

    public static Optional<SocketNamespace> parse(String path, WizSocketProperties properties) {
        if (path == null) {
            return Optional.empty();
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        WizSocketProperties socketProperties = properties == null ? new WizSocketProperties() : properties;
        String socketPrefix = socketProperties.getPath() + "/";
        if (!normalized.startsWith(socketPrefix)) {
            return Optional.empty();
        }
        String[] parts = normalized.substring(socketPrefix.length()).split("/");
        if (parts.length == 1 && !parts[0].isBlank()) {
            return Optional.of(new SocketNamespace(parts[0]));
        }
        return Optional.empty();
    }

    public String socketIoPath() {
        return socketIoPath(new WizSocketProperties());
    }

    public String socketIoPath(WizSocketProperties properties) {
        WizSocketProperties socketProperties = properties == null ? new WizSocketProperties() : properties;
        return socketProperties.getPath() + "/" + appId;
    }

    public String path() {
        return path(new WizSocketProperties());
    }

    public String path(WizSocketProperties properties) {
        return socketIoPath(properties);
    }
}
