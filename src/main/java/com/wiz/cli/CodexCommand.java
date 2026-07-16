package com.wiz.cli;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import com.wiz.runtime.PathService;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "codex",
        mixinStandardHelpOptions = true,
        description = "Generate or check .codex settings and bundled WIZ Spring instructions. Writes server name 'wiz-spring' for MCP.")
public class CodexCommand implements Callable<Integer> {

    private static final String INSTRUCTION_MANIFEST = "/wiz/codex-instructions.files";
    private static final String INSTRUCTION_RESOURCE_ROOT = "/wiz/codex-instructions/";

    @Option(names = "--root", description = "Target WIZ Spring workspace root. Defaults to auto-detecting from the current directory.")
    private Path root;

    @Option(names = "--runtime-jar", description = "wiz-spring executable jar path for generated MCP args. Defaults to the currently running jar.")
    private Path runtimeJar;

    @Option(names = "--force", description = "Overwrite existing generated settings and bundled instruction files when content differs.")
    private boolean force;

    @Option(names = "--check", description = "Only check generated content. Missing or outdated files return exit code 2.")
    private boolean check;

    @Override
    public Integer call() throws Exception {
        Path workspaceRoot = workspaceRoot(root);
        PathService pathService = new PathService(workspaceRoot);
        if (!pathService.isWorkspaceRoot(workspaceRoot)) {
            throw new IllegalArgumentException("WIZ workspace root not found: " + workspaceRoot);
        }

        Path jar = runtimeJar == null ? currentRuntimePath() : runtimeJar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("wiz-spring runtime jar not found: " + jar + " (use --runtime-jar)");
        }

        Map<Path, String> files = desiredFiles(workspaceRoot, jar);
        boolean blocked = false;
        for (Map.Entry<Path, String> entry : files.entrySet()) {
            Path path = entry.getKey();
            String desired = entry.getValue();
            if (!Files.exists(path)) {
                if (check) {
                    System.out.println("Missing: " + path);
                    blocked = true;
                } else {
                    write(path, desired);
                    System.out.println("Created: " + path);
                }
                continue;
            }

            String current = Files.readString(path);
            if (current.equals(desired)) {
                System.out.println("Up to date: " + path);
                continue;
            }

            if (check) {
                System.out.println("Outdated: " + path);
                blocked = true;
            } else if (force) {
                write(path, desired);
                System.out.println("Updated: " + path);
            } else {
                System.out.println("Warning: " + path + " differs from generated WIZ Spring Codex settings.");
                blocked = true;
            }
        }

        if (blocked) {
            System.out.println("Use --force to overwrite existing files.");
            return 2;
        }
        System.out.println("Codex settings and WIZ Spring instructions ready: " + workspaceRoot);
        return 0;
    }

    private Map<Path, String> desiredFiles(Path workspaceRoot, Path jar) throws IOException {
        Path codexRoot = workspaceRoot.resolve(".codex");
        LinkedHashMap<Path, String> files = new LinkedHashMap<>();
        files.put(codexRoot.resolve("config.toml"), configToml(workspaceRoot, jar));
        files.put(codexRoot.resolve("AGENTS.md"), agentsMarkdown());
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
        try (InputStream input = CodexCommand.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("Bundled WIZ Spring instruction not found: " + resourcePath);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String configToml(Path workspaceRoot, Path jar) {
        return """
                approval_policy = "on-failure"
                sandbox_mode = "danger-full-access"

                [sandbox_workspace_write]
                network_access = true
                writable_roots = [%s, "/tmp"]

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
                toml(jar.toString()),
                toml(workspaceRoot.toString()),
                toml(workspaceRoot.resolve(".wiz/mcp-state.json").toString()),
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

    private Path workspaceRoot(Path root) {
        return WorkspaceRootResolver.resolve(root, "codex");
    }

    private Path currentRuntimePath() {
        String classPath = System.getProperty("java.class.path", "");
        if (!classPath.isBlank() && !classPath.contains(File.pathSeparator)) {
            Path candidate = Path.of(classPath).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        try {
            return Path.of(CodexCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Failed to resolve current runtime jar path", exception);
        }
    }

    private void write(Path path, String content) throws java.io.IOException {
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
}
