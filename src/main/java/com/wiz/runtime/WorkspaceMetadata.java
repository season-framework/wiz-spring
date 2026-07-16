package com.wiz.runtime;

import java.util.Optional;
import java.util.Properties;

public record WorkspaceMetadata(
        String workspace,
        int formatVersion,
        String runtimeName,
        String runtimeVersion) {

    public static final String JAVA_WORKSPACE = "java";
    public static final int CURRENT_FORMAT_VERSION = 1;
    public static final String RUNTIME_NAME = "wiz-spring";

    public WorkspaceMetadata {
        workspace = required(workspace, "workspace");
        if (formatVersion < 0) {
            throw new IllegalArgumentException("Workspace format version must not be negative");
        }
        runtimeName = value(runtimeName);
        runtimeVersion = value(runtimeVersion);
    }

    public static WorkspaceMetadata current() {
        return new WorkspaceMetadata(JAVA_WORKSPACE, CURRENT_FORMAT_VERSION, RUNTIME_NAME, WizSpringVersion.current());
    }

    public static Optional<WorkspaceMetadata> from(Properties properties) {
        if (properties == null) {
            return Optional.empty();
        }
        String workspace = properties.getProperty("workspace");
        if (workspace == null || workspace.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new WorkspaceMetadata(
                workspace,
                nonNegativeInt(properties.getProperty("format-version")),
                properties.getProperty("runtime.name"),
                properties.getProperty("runtime.version")));
    }

    public boolean isJava() {
        return JAVA_WORKSPACE.equalsIgnoreCase(workspace);
    }

    public String yaml() {
        return "# WIZ workspace metadata. Runtime settings live in application*.yml.\n"
                + "workspace: " + scalar(workspace) + "\n"
                + "format-version: " + formatVersion + "\n"
                + "runtime:\n"
                + "  name: " + scalar(runtimeName) + "\n"
                + "  version: " + scalar(runtimeVersion) + "\n";
    }

    private static int nonNegativeInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String required(String value, String name) {
        String normalized = value(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return normalized;
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    private static String scalar(String value) {
        return "\"" + value(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") + "\"";
    }
}
