package com.wiz.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.wiz.runtime.PathService;
import com.wiz.runtime.WorkspaceRuntimePaths;

public final class CodexWorkspaceService {

    private static final String INSTRUCTION_MANIFEST = "/wiz/codex-instructions.files";
    private static final String INSTRUCTION_RESOURCE_ROOT = "/wiz/codex-instructions/";

    public SetupResult setup(Path workspaceRoot, Path runtimeJar) throws IOException {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("WIZ workspace root is required");
        }
        Path root = workspaceRoot.toAbsolutePath().normalize();
        if (!new PathService(root).isWorkspaceRoot(root)) {
            throw new IllegalArgumentException("WIZ workspace root not found: " + root);
        }
        if (runtimeJar == null) {
            throw new IllegalArgumentException("wiz-spring runtime jar is required");
        }
        Path jar = runtimeJar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("wiz-spring runtime jar not found: " + jar);
        }

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        for (Map.Entry<Path, String> entry : desiredFiles(root, jar).entrySet()) {
            Path path = entry.getKey();
            String desired = entry.getValue();
            if (!Files.exists(path)) {
                write(path, desired);
                created++;
            } else if (Files.readString(path).equals(desired)) {
                unchanged++;
            } else {
                write(path, desired);
                updated++;
            }
        }
        return new SetupResult(root, jar, created, updated, unchanged);
    }

    private Map<Path, String> desiredFiles(Path workspaceRoot, Path jar) throws IOException {
        LinkedHashMap<Path, String> files = new LinkedHashMap<>();
        files.put(workspaceRoot.resolve(".codex/config.toml"), configToml(workspaceRoot, jar));
        files.put(workspaceRoot.resolve(".codex/AGENTS.md"), agentsMarkdown());
        addBundledInstructions(files, workspaceRoot);
        return files;
    }

    private void addBundledInstructions(Map<Path, String> files, Path workspaceRoot) throws IOException {
        Path instructionRoot = workspaceRoot.resolve(".github").toAbsolutePath().normalize();
        String manifest = readResource(INSTRUCTION_MANIFEST);
        for (String rawEntry : manifest.lines().toList()) {
            String entry = rawEntry.strip();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            if (entry.startsWith("/") || entry.contains("\\")) {
                throw new IllegalStateException("Invalid bundled instruction path: " + entry);
            }

            Path target = instructionRoot.resolve(entry).normalize();
            if (target.equals(instructionRoot) || !target.startsWith(instructionRoot)) {
                throw new IllegalStateException("Bundled instruction path escapes .github: " + entry);
            }
            if (files.put(target, readResource(INSTRUCTION_RESOURCE_ROOT + entry)) != null) {
                throw new IllegalStateException("Duplicate bundled instruction path: " + entry);
            }
        }
    }

    private String readResource(String resourcePath) throws IOException {
        try (InputStream input = CodexWorkspaceService.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("Bundled WIZ Spring instruction not found: " + resourcePath);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String configToml(Path workspaceRoot, Path jar) {
        Path mcpState = WorkspaceRuntimePaths.mcpState(workspaceRoot);
        return """
                approval_policy = "on-failure"
                sandbox_mode = "danger-full-access"

                [sandbox_workspace_write]
                network_access = true
                writable_roots = [%s, %s, "/tmp"]

                [mcp_servers."wiz-spring"]
                command = "java"
                args = [
                  "-jar",
                  %s,
                  "mcp",
                  "--root",
                  %s,
                  "--state",
                  %s,
                ]

                [mcp_servers."wiz-spring".env]
                WIZ_WORKSPACE = %s

                [mcp_servers."wiz-spring".tools.wiz_workspace_status]
                approval_mode = "approve"

                [mcp_servers."wiz-spring".tools.wiz_app_info]
                approval_mode = "approve"

                [mcp_servers."wiz-spring".tools.wiz_source_update_app]
                approval_mode = "approve"

                [mcp_servers."wiz-spring".tools.wiz_app_read_file]
                approval_mode = "approve"

                [mcp_servers."wiz-spring".tools.wiz_app_build]
                approval_mode = "approve"

                [mcp_servers."wiz-spring".tools.wiz_app_jar]
                approval_mode = "approve"

                [mcp_servers."wiz-spring".tools.wiz_app_dependency_info]
                approval_mode = "approve"

                [mcp_servers."wiz-spring".tools.wiz_source_create_controller]
                approval_mode = "approve"

                [mcp_servers."wiz-spring".tools.wiz_source_delete_controller]
                approval_mode = "approve"

                [mcp_servers."wiz-spring".tools.wiz_package_delete]
                approval_mode = "approve"

                [projects.%s]
                trust_level = "trusted"
                """.formatted(
                toml(workspaceRoot.toString()),
                toml(mcpState.getParent().toString()),
                toml(jar.toString()),
                toml(workspaceRoot.toString()),
                toml(mcpState.toString()),
                toml(workspaceRoot.toString()),
                toml(workspaceRoot.toString()));
    }

    private String agentsMarkdown() {
        return """
                # Codex Instructions

                Follow `.github/copilot-instructions.md`.

                Use the relevant references under `.github/devdocs/` and reusable prompts under `.github/prompts/`.

                Before code changes, if `.github/custom/custom-instructions.md` exists, read it first.
                If that file lists reference files, read the relevant referenced files too.

                For WIZ Spring work:
                - Use the standalone WIZ Spring MCP tools first. This workspace must not depend on the old `wiz-vscode` extension MCP.
                - Start with `wiz_workspace_status`.
                - Modify only the current WIZ workspace/app.
                - Use `wiz_source_*`, `wiz_package_*`, and `wiz_app_*` according to the target path.
                - Treat `src/app/**/api.java`, `route.java`, `socket.java`, `src/controller`, `src/portal`, `pom.xml`, and `src/angular/package.json` as the main Spring app surfaces.
                - Do not add Python/Flask, virtualenv, or pip workflows for app code. Use workspace `pom.xml` for Java dependencies and `src/angular/package.json` for frontend dependencies.
                - Prefer Spring-specific MCP tools when relevant: `wiz_app_dependency_info`, `wiz_app_jar`, `wiz_source_create_controller`, `wiz_source_delete_controller`, and `wiz_package_delete`.

                ## Devlog Enforcement

                For every task that changes workspace source files, write the devlog before the final response.

                - Check the current workspace `devlog.md` and `devlog/{YYYY-MM-DD}/` before finishing.
                - Add one summary row to `devlog.md` and one matching detail file under `devlog/{YYYY-MM-DD}/`.
                - Include the user's original request, changed files, and verification result in the detail file.
                - If prior work in the same session missed devlogs, add a catch-up devlog that records the missed work before reporting completion.
                """;
    }

    private void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static String toml(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\b' -> result.append("\\b");
                case '\t' -> result.append("\\t");
                case '\n' -> result.append("\\n");
                case '\f' -> result.append("\\f");
                case '\r' -> result.append("\\r");
                default -> {
                    if (c < 0x20) {
                        result.append(String.format("\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
                }
            }
        }
        result.append('"');
        return result.toString();
    }

    public record SetupResult(Path workspaceRoot, Path runtimeJar, int createdFiles, int updatedFiles, int unchangedFiles) {

        public int changedFiles() {
            return createdFiles + updatedFiles;
        }

        public int managedFiles() {
            return changedFiles() + unchangedFiles;
        }
    }
}
