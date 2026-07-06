package com.wiz.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

public record ProjectResourceHealth(Status status, Map<String, Object> details) {

    public enum Status {
        UP,
        DOWN,
        UNKNOWN
    }

    public ProjectResourceHealth {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static ProjectResourceHealth up() {
        return up(Map.of());
    }

    public static ProjectResourceHealth up(Map<String, Object> details) {
        return new ProjectResourceHealth(Status.UP, details);
    }

    public static ProjectResourceHealth down(String error) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        if (error != null && !error.isBlank()) {
            details.put("error", error);
        }
        return new ProjectResourceHealth(Status.DOWN, details);
    }

    public static ProjectResourceHealth unknown(String reason) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        if (reason != null && !reason.isBlank()) {
            details.put("reason", reason);
        }
        return new ProjectResourceHealth(Status.UNKNOWN, details);
    }
}
