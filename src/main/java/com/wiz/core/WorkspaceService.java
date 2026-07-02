package com.wiz.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

public class WorkspaceService {

    private static final int DEFAULT_PORT = 3000;
    private final SecureRandom secureRandom = new SecureRandom();
    private final int startPort;

    public WorkspaceService() {
        this(DEFAULT_PORT);
    }

    WorkspaceService(int startPort) {
        PortFinder.validatePort(startPort);
        if (startPort == 0) {
            throw new IllegalArgumentException("Workspace start port must be greater than 0");
        }
        this.startPort = startPort;
    }

    public CreatedWorkspace createWorkspace(Path requestedPath) throws IOException {
        if (requestedPath == null) {
            throw new IllegalArgumentException("Workspace path is required");
        }
        Path root = requestedPath.toAbsolutePath().normalize();
        if (Files.exists(root)) {
            throw new IllegalArgumentException("Workspace path already exists: " + root);
        }

        int port = PortFinder.nextAvailablePort(startPort);
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("project"));
        Files.writeString(root.resolve("config/application.yml"), workspaceConfig(port));
        Files.writeString(root.resolve("config/wiz.yml"), "# WIZ workspace marker. Runtime settings live in application.yml.\nworkspace: java\n");
        return new CreatedWorkspace(root, port);
    }

    private String workspaceConfig(int port) {
        return "# Workspace-level runtime settings.\n"
                + "# Keep server.port here so project configs do not conflict with the workspace port.\n"
                + "server:\n"
                + "  port: " + port + "\n"
                + "wiz:\n"
                + "  project:\n"
                + "    default-name: main\n"
                + "  secret: \"" + secret() + "\"\n";
    }

    private String secret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public record CreatedWorkspace(Path root, int port) {
    }
}
