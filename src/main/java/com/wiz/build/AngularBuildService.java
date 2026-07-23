package com.wiz.build;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
        Path angularRoot = ProjectBuildLayout.stagedAngularRoot(project);
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
            buildLogger.info("[frontend-stage] staging WIZ sources for Angular CLI");
            sourceStagingService.stage(project);
            Optional<BuildReadiness> readiness = buildReadiness(angularRoot, true);
            if (readiness.isEmpty()) {
                buildLogger.info("[frontend] Angular package is present but not self-contained for CLI build; using minimal web bundle fallback");
                return FrontendBuildResult.skipped("Angular package is present but not self-contained for CLI build; using minimal web bundle fallback");
            }

            String dependencyFingerprint = dependencyFingerprint(angularRoot);
            boolean dependenciesPresent = frontendDependenciesPresent(angularRoot);
            boolean dependencyLockPresent = dependencyLockPresent(angularRoot);
            boolean dependencyFingerprintMatches = dependencyLockPresent
                    && dependencyFingerprintMatches(project, dependencyFingerprint);
            boolean installDependencies = clean || !dependenciesPresent || !dependencyFingerprintMatches;
            if (installDependencies) {
                String reason = clean
                        ? "clean build"
                        : (!dependenciesPresent
                                ? "dependencies are missing"
                                : (!dependencyLockPresent
                                        ? "dependency lockfile is missing"
                                        : "package metadata changed"));
                buildLogger.info("[frontend-install] required: " + reason);
                delete(angularRoot.resolve(".angular/cache"));
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
                writeDependencyFingerprint(project, dependencyFingerprint);
            } else {
                buildLogger.info("[frontend-install] skipped; dependencies match package metadata");
            }

            buildLogger.info("[frontend-pug] compiling Pug templates when present");
            CommandResult pug = pugBuildService.compile(project, angularRoot, buildLogger);
            commands.add(pug);
            logCommandResult(buildLogger, pug);
            if (!pug.success()) {
                return FrontendBuildResult.failed(pug.summary(), commands);
            }

            readiness = buildReadiness(angularRoot, false);
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

            copyAngularOutput(angularRoot, readiness.get().outputPath(), ProjectBuildLayout.frontendOutputRoot(project));
            buildLogger.info("[frontend-build] output copied to " + ProjectBuildLayout.frontendOutputRoot(project));
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

    private String dependencyFingerprint(Path angularRoot) throws IOException {
        MessageDigest digest = sha256();
        for (String name : List.of("package.json", "package-lock.json", "npm-shrinkwrap.json", ".npmrc")) {
            Path input = angularRoot.resolve(name);
            if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            digest.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (var stream = Files.newInputStream(input)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private boolean dependencyFingerprintMatches(ProjectContext project, String fingerprint) throws IOException {
        Path state = ProjectBuildLayout.frontendDependencyFingerprint(project);
        return Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS)
                && Files.readString(state).trim().equals(fingerprint);
    }

    private void writeDependencyFingerprint(ProjectContext project, String fingerprint) throws IOException {
        Path state = ProjectBuildLayout.frontendDependencyFingerprint(project);
        Files.createDirectories(state.getParent());
        Path temporary = state.resolveSibling(state.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, fingerprint + System.lineSeparator());
            try {
                Files.move(temporary, state, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, state, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private Optional<BuildReadiness> buildReadiness(Path angularRoot, boolean allowPugIndex) throws IOException {
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
        String outputPath = outputPath(options);
        Path indexPath = angularRoot.resolve(index);
        if (!(Files.isRegularFile(indexPath) || (allowPugIndex && Files.isRegularFile(pugAlternative(indexPath))))
                || !Files.isRegularFile(angularRoot.resolve(main))
                || !Files.isRegularFile(angularRoot.resolve(tsConfig))) {
            return Optional.empty();
        }
        return Optional.of(new BuildReadiness(outputPath));
    }

    private Path pugAlternative(Path indexPath) {
        String name = indexPath.getFileName().toString();
        if (!name.endsWith(".html")) {
            return indexPath.resolveSibling(name + ".pug");
        }
        return indexPath.resolveSibling(name.substring(0, name.length() - ".html".length()) + ".pug");
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
        if (dependencyLockPresent(angularRoot)) {
            return "ci";
        }
        return "install";
    }

    private boolean dependencyLockPresent(Path angularRoot) {
        return Files.isRegularFile(angularRoot.resolve("package-lock.json"), LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(angularRoot.resolve("npm-shrinkwrap.json"), LinkOption.NOFOLLOW_LINKS);
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

    private String outputPath(Map<String, Object> options) {
        Object value = options.get("outputPath");
        if (value instanceof Map<?, ?> outputPath) {
            Object base = outputPath.get("base");
            return base == null || base.toString().isBlank() ? "dist/build" : base.toString();
        }
        return value == null || value.toString().isBlank() ? "dist/build" : value.toString();
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
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
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
