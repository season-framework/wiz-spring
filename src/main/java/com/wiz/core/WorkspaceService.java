package com.wiz.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

import com.wiz.runtime.PathService;

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
        return createWorkspace(requestedPath, PathService.DEFAULT_PACKAGE_ROOT);
    }

    public CreatedWorkspace createWorkspace(Path requestedPath, String packageRoot) throws IOException {
        if (requestedPath == null) {
            throw new IllegalArgumentException("Workspace path is required");
        }
        String javaPackageRoot = new PathService(Path.of(".")).validatePackageRoot(packageRoot);
        Path root = requestedPath.toAbsolutePath().normalize();
        if (Files.exists(root)) {
            throw new IllegalArgumentException("Workspace path already exists: " + root);
        }

        int port = PortFinder.nextAvailablePort(startPort);
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/application.yml"), workspaceConfig(port, javaPackageRoot));
        Files.writeString(root.resolve("config/wiz.yml"), "# WIZ workspace marker. Runtime settings live in application.yml.\nworkspace: java\n");
        return new CreatedWorkspace(root, port);
    }

    private String workspaceConfig(int port, String packageRoot) {
        return "# WIZ Spring runtime settings.\n"
                + "server:\n"
                + "  port: " + port + "\n"
                + "wiz:\n"
                + "  java:\n"
                + "    package-root: " + packageRoot + "\n"
                + "  api:\n"
                + "    prefix: /wiz/api\n"
                + "  http:\n"
                + "    max-request-body-bytes: 0\n"
                + "  socket:\n"
                + "    allowed-origins:\n"
                + "      - \"*\"\n"
                + "    polling-session-ttl-millis: 120000\n"
                + "    max-polling-sessions: 1024\n"
                + "    polling-queue-capacity: 256\n"
                + "  redirect:\n"
                + "    policy: any\n"
                + "    allowed-hosts: []\n"
                + "  runtime:\n"
                + "    devmode-cookie-name: season-wiz-devmode\n"
                + "    warmup-enabled: true\n"
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
