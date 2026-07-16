package com.wiz.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.wiz.runtime.PathService;
import com.wiz.runtime.WorkspaceMetadata;

public class WorkspaceService {

    private static final int DEFAULT_PORT = 3000;
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
        Files.writeString(root.resolve("config/wiz.yml"), WorkspaceMetadata.current().yaml());
        return new CreatedWorkspace(root, port);
    }

    private String workspaceConfig(int port, String packageRoot) {
        return "# WIZ Spring runtime settings. 모든 profile에서 먼저 읽는 공통 설정입니다.\n"
                + "# 이 파일과 application-<profile>.yml은 로컬 값이나 비밀 값을 포함할 수 있어 기본 .gitignore 대상입니다.\n"
                + "# 공유할 설정은 application*.example.yml에 반영하고 실제 비밀 값은 커밋하지 마세요.\n"
                + "# 공통 session cookie 보안 정책은 이 파일에 있고 Secure 여부는 dev/prod profile에서 결정합니다.\n"
                + "server:\n"
                + "  port: " + port + "\n"
                + "  servlet:\n"
                + "    session:\n"
                + "      tracking-modes:\n"
                + "        - cookie\n"
                + "      cookie:\n"
                + "        http-only: true\n"
                + "        same-site: lax\n"
                + "wiz:\n"
                + "  java:\n"
                + "    package-root: " + packageRoot + "\n";
    }

    public record CreatedWorkspace(Path root, int port) {
    }
}
