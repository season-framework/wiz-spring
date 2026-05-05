package com.wiz.core;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

public class WorkspaceService {

    private static final int DEFAULT_PORT = 3000;
    private final SecureRandom secureRandom = new SecureRandom();

    public CreatedWorkspace createWorkspace(Path requestedPath) throws IOException {
        if (requestedPath == null) {
            throw new IllegalArgumentException("Workspace path is required");
        }
        Path root = requestedPath.toAbsolutePath().normalize();
        if (Files.exists(root)) {
            throw new IllegalArgumentException("Workspace path already exists: " + root);
        }

        int port = nextAvailablePort(DEFAULT_PORT);
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("project"));
        Files.writeString(root.resolve("config/application.yml"), workspaceConfig(port));
        Files.writeString(root.resolve("config/wiz.yml"), "workspace: java\n");
        return new CreatedWorkspace(root, port);
    }

    private String workspaceConfig(int port) {
        return "server:\n"
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

    private int nextAvailablePort(int startPort) {
        int port = startPort;
        while (!isAvailable(port)) {
            port++;
        }
        return port;
    }

    private boolean isAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    public record CreatedWorkspace(Path root, int port) {
    }
}
