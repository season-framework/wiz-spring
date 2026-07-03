package com.wiz.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.wiz.runtime.ProjectContext;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public class AngularBuildService {

    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(5);
    private static final int OUTPUT_CAP_BYTES = 64 * 1024;

    private final CommandExecutor commandExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AngularSourceStagingService sourceStagingService = new AngularSourceStagingService();
    private final PugBuildService pugBuildService;

    public AngularBuildService() {
        this(new CommandExecutor());
    }

    public AngularBuildService(CommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor;
        this.pugBuildService = new PugBuildService(commandExecutor);
    }

    public FrontendBuildResult build(ProjectContext project) throws IOException {
        return build(project, true, BuildLogger.quiet());
    }

    public FrontendBuildResult build(ProjectContext project, BuildLogger logger) throws IOException {
        return build(project, true, logger);
    }

    public FrontendBuildResult build(ProjectContext project, boolean clean, BuildLogger logger) throws IOException {
        BuildLogger buildLogger = logger == null ? BuildLogger.quiet() : logger;
        Path angularRoot = project.buildRoot().resolve("src/angular");
        if (!Files.isRegularFile(angularRoot.resolve("package.json"))) {
            buildLogger.info("[frontend] No src/angular/package.json; using minimal web bundle fallback");
            return FrontendBuildResult.skipped("No src/angular/package.json; using minimal web bundle fallback");
        }
        if (!Files.isRegularFile(angularRoot.resolve("angular.json"))) {
            buildLogger.info("[frontend] Angular package is present but angular.json is missing; using minimal web bundle fallback");
            return FrontendBuildResult.skipped("Angular package is present but angular.json is missing; using minimal web bundle fallback");
        }
        if (unitTestRuntimeWithRealExecutor()) {
            buildLogger.info("[frontend] Real Angular build is disabled during Maven unit tests; using minimal web bundle fallback");
            return FrontendBuildResult.skipped("Real Angular build is disabled during Maven unit tests; using minimal web bundle fallback");
        }

        ArrayList<CommandResult> commands = new ArrayList<>();
        try {
            if (clean) {
                buildLogger.info("[frontend-install] command: npm " + installCommand(angularRoot));
                CommandResult install = commandExecutor.run(
                        "frontend-install",
                        project.root(),
                        angularRoot,
                        List.of("npm", installCommand(angularRoot)),
                        INSTALL_TIMEOUT,
                        OUTPUT_CAP_BYTES,
                        buildLogger);
                commands.add(install);
                logCommandResult(buildLogger, install);
                if (!install.success()) {
                    return FrontendBuildResult.failed(install.summary(), commands);
                }
            } else {
                buildLogger.info("[frontend-install] skipped for normal build");
                if (!frontendDependenciesPresent(angularRoot)) {
                    return FrontendBuildResult.failed("Frontend dependencies are missing; run build with --clean to install npm packages", commands);
                }
            }

            buildLogger.info("[frontend-stage] staging WIZ sources for Angular CLI");
            sourceStagingService.stage(project);
            buildLogger.info("[frontend-pug] compiling Pug templates when present");
            CommandResult pug = pugBuildService.compile(project, angularRoot, buildLogger);
            commands.add(pug);
            logCommandResult(buildLogger, pug);
            if (!pug.success()) {
                return FrontendBuildResult.failed(pug.summary(), commands);
            }

            Optional<BuildReadiness> readiness = buildReadiness(angularRoot);
            if (readiness.isEmpty()) {
                buildLogger.info("[frontend] Angular package is present but not self-contained for CLI build; using minimal web bundle fallback");
                return FrontendBuildResult.skipped("Angular package is present but not self-contained for CLI build; using minimal web bundle fallback");
            }

            buildLogger.info("[frontend-build] command: " + angularRoot.resolve("node_modules/.bin/ng") + " build");
            CommandResult build = commandExecutor.run(
                    "frontend-build",
                    project.root(),
                    angularRoot,
                    List.of(angularRoot.resolve("node_modules/.bin/ng").toString(), "build"),
                    BUILD_TIMEOUT,
                    OUTPUT_CAP_BYTES,
                    buildLogger);
            commands.add(build);
            logCommandResult(buildLogger, build);
            if (!build.success()) {
                return FrontendBuildResult.failed(build.summary(), commands);
            }

            copyAngularOutput(angularRoot, readiness.get().outputPath(), project.buildRoot().resolve("dist/build"));
            buildLogger.info("[frontend-build] output copied to " + project.buildRoot().resolve("dist/build"));
            return FrontendBuildResult.built(commands);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return FrontendBuildResult.failed("Angular build interrupted", commands);
        } catch (IllegalArgumentException exception) {
            return FrontendBuildResult.failed(exception.getMessage(), commands);
        }
    }

    private boolean frontendDependenciesPresent(Path angularRoot) {
        return Files.exists(angularRoot.resolve("node_modules/.bin/ng"))
                && Files.isDirectory(angularRoot.resolve("node_modules/pug"));
    }

    private Optional<BuildReadiness> buildReadiness(Path angularRoot) throws IOException {
        Path angularJson = angularRoot.resolve("angular.json");
        if (!Files.isRegularFile(angularJson)) {
            return Optional.empty();
        }
        Map<String, Object> metadata = objectMapper.readValue(Files.readAllBytes(angularJson), new TypeReference<LinkedHashMap<String, Object>>() {
        });
        Map<String, Object> options = buildOptions(metadata).orElse(Map.of());
        String index = string(options, "index", "src/index.html");
        String main = string(options, "main", "src/main.ts");
        String tsConfig = string(options, "tsConfig", "tsconfig.app.json");
        String outputPath = string(options, "outputPath", "dist/build");
        if (!Files.isRegularFile(angularRoot.resolve(index))
                || !Files.isRegularFile(angularRoot.resolve(main))
                || !Files.isRegularFile(angularRoot.resolve(tsConfig))) {
            return Optional.empty();
        }
        return Optional.of(new BuildReadiness(outputPath));
    }

    private Optional<Map<String, Object>> buildOptions(Map<String, Object> angularJson) {
        Object projects = angularJson.get("projects");
        if (!(projects instanceof Map<?, ?> projectMap) || projectMap.isEmpty()) {
            return Optional.empty();
        }
        Object project = projectMap.values().iterator().next();
        if (!(project instanceof Map<?, ?> projectConfig)) {
            return Optional.empty();
        }
        Object architect = projectConfig.get("architect");
        if (!(architect instanceof Map<?, ?> architectConfig)) {
            return Optional.empty();
        }
        Object build = architectConfig.get("build");
        if (!(build instanceof Map<?, ?> buildConfig)) {
            return Optional.empty();
        }
        Object options = buildConfig.get("options");
        if (!(options instanceof Map<?, ?> optionMap)) {
            return Optional.empty();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        optionMap.forEach((key, value) -> result.put(String.valueOf(key), value));
        return Optional.of(result);
    }

    private String installCommand(Path angularRoot) {
        if (Files.isRegularFile(angularRoot.resolve("package-lock.json")) || Files.isRegularFile(angularRoot.resolve("npm-shrinkwrap.json"))) {
            return "ci";
        }
        return "install";
    }

    private void logCommandResult(BuildLogger logger, CommandResult result) {
        logger.info("[" + result.phase() + "] exitCode=" + result.exitCode()
                + " duration=" + formatDuration(result.durationMillis())
                + " timedOut=" + result.timedOut()
                + " cappedOutput=" + result.cappedOutput());
    }

    private String formatDuration(long millis) {
        if (millis < 1000) {
            return millis + "ms";
        }
        return String.format(java.util.Locale.ROOT, "%.2fs", millis / 1000.0);
    }

    private boolean unitTestRuntimeWithRealExecutor() {
        // Maven unit tests should not run network-bound npm/ng builds; the smoke script covers the real CLI path.
        return System.getProperty("surefire.test.class.path") != null
                && commandExecutor.getClass().equals(CommandExecutor.class);
    }

    private void copyAngularOutput(Path angularRoot, String outputPath, Path target) throws IOException {
        Path produced = angularRoot.resolve(outputPath).toAbsolutePath().normalize();
        Path normalizedAngularRoot = angularRoot.toAbsolutePath().normalize();
        if (!produced.startsWith(normalizedAngularRoot)) {
            throw new IllegalArgumentException("Angular output path escapes angular root");
        }
        Path source = Files.isRegularFile(produced.resolve("browser/index.html")) ? produced.resolve("browser") : produced;
        if (!Files.isRegularFile(source.resolve("index.html"))) {
            throw new IllegalArgumentException("Angular build did not produce index.html at " + source);
        }
        delete(target);
        copyDirectory(source, target);
    }

    private String string(Map<String, Object> metadata, String key, String defaultValue) {
        Object value = metadata.get(key);
        return value == null || value.toString().isBlank() ? defaultValue : value.toString();
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path item : paths.toList()) {
                Path relative = source.relativize(item);
                Path destination = target.resolve(relative.toString()).normalize();
                if (!destination.startsWith(target.normalize())) {
                    throw new IllegalArgumentException("Angular output copy escapes target directory");
                }
                if (Files.isSymbolicLink(item)) {
                    throw new IllegalArgumentException("Symbolic links are not allowed in Angular output: " + relative);
                }
                if (Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(item, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void delete(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    private record BuildReadiness(String outputPath) {
    }
}
