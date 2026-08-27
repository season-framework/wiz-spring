package com.wiz.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Command(name = "service", mixinStandardHelpOptions = true, description = "Manage WIZ Spring systemd services.", subcommands = {
        ServiceCommand.ListServices.class,
        ServiceCommand.Install.class,
        ServiceCommand.Uninstall.class,
        ServiceCommand.Status.class,
        ServiceCommand.Logs.class,
        ServiceCommand.Start.class,
        ServiceCommand.Stop.class,
        ServiceCommand.Restart.class
})
public class ServiceCommand implements Callable<Integer> {

    private static final Path DEFAULT_SYSTEMD_DIR = Path.of("/etc/systemd/system");
    private static final Path DEFAULT_BIN_DIR = Path.of("/usr/local/bin");
    private static final String BUNDLE_MANIFEST = "manifest.json";
    private static final String BUNDLE_CHECKSUMS = "SHA256SUMS";
    private static final Set<Path> MUTABLE_BUNDLE_FILES = Set.of(Path.of(".env"));

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "List WIZ services.")
    static class ListServices implements Callable<Integer> {
        @Spec
        private CommandSpec spec;

        @Option(names = "--systemd-dir", hidden = true)
        private Path systemdDir = DEFAULT_SYSTEMD_DIR;

        @Option(names = "--bin-dir", hidden = true)
        private Path binDir = DEFAULT_BIN_DIR;

        @Override
        public Integer call() throws Exception {
            ensureLinux();
            printServices(describeServices(systemdDir, binDir), spec.commandLine().getOut());
            return 0;
        }
    }

    @Command(name = "install", mixinStandardHelpOptions = true, description = "Install a WIZ systemd service from a 1.0 bundle.")
    static class Install implements Callable<Integer> {
        @Spec
        private CommandSpec spec;

        @Parameters(index = "0", description = "Service name.")
        private String name;

        @Option(names = "--bundle", required = true,
                description = "Bundle directory containing manifest.json and the executable backend artifact.")
        private Path bundle;

        @Option(names = "--java", description = "Absolute Java executable path.")
        private String javaCommand = defaultJavaCommand();

        @Option(names = "--artifact", description = "Backend archive override beneath --bundle.")
        private Path artifact;

        @Option(names = "--port", description = "Override the Spring Boot HTTP port.")
        private Integer port;

        @Option(names = "--user", description = "Operating-system user for the service. Defaults to the bundle owner.")
        private String user;

        @Option(names = "--allow-root",
                description = "Explicitly allow the systemd service to run as root (not recommended).")
        private boolean allowRoot;

        @Option(names = "--profiles", defaultValue = "prod,bundle",
                description = "Comma-separated Spring profiles. Defaults to ${DEFAULT-VALUE}.")
        private String profiles;

        @Option(names = "--dry-run", description = "Print generated files without writing them.")
        private boolean dryRun;

        @Option(names = "--systemd-dir", hidden = true)
        private Path systemdDir = DEFAULT_SYSTEMD_DIR;

        @Option(names = "--bin-dir", hidden = true)
        private Path binDir = DEFAULT_BIN_DIR;

        @Option(names = "--systemctl", hidden = true)
        private Path systemctl = Path.of("systemctl");

        @Override
        public Integer call() throws Exception {
            ensureLinux();
            String serviceName = serviceName(name);
            Path rootPath = serviceRoot(null, bundle);
            requireSingleLine("Workspace root", rootPath.toString());
            Path bundlePath = bundlePath(rootPath, bundle);
            BundleArtifact resolvedArtifact = resolveBundleArtifact(bundlePath, artifact);
            verifyBundleChecksums(bundlePath);
            Path javaPath = javaExecutable(javaCommand);
            Path commandPath = binDir.resolve(serviceName);
            Path servicePath = systemdDir.resolve(serviceName + ".service");
            requireSystemdExecutablePath(commandPath);
            requireSingleLine("Service definition path", servicePath.toString());
            String serviceUser = serviceUser(user, bundlePath, allowRoot);
            requireBundleAccessibleToServiceUser(resolvedArtifact, javaPath, serviceUser);
            String activeProfiles = normalizeProfiles(profiles);
            validatePort(port);
            String script = script(
                    serviceName, javaPath, rootPath, resolvedArtifact, port, activeProfiles);
            String unit = unit(serviceName, commandPath, serviceUser);
            if (dryRun) {
                var out = spec.commandLine().getOut();
                out.println(commandPath);
                out.println(script);
                out.println(servicePath);
                out.println(unit);
                return 0;
            }
            Files.createDirectories(commandPath.getParent());
            Files.createDirectories(servicePath.getParent());
            Files.writeString(commandPath, script, StandardCharsets.UTF_8);
            commandPath.toFile().setExecutable(true, false);
            Files.writeString(servicePath, unit, StandardCharsets.UTF_8);
            int reloadExit = runSystemctl(systemctl, "daemon-reload");
            if (reloadExit != 0) {
                return reloadExit;
            }
            int enableExit = runSystemctl(systemctl, "enable", "--now", serviceName);
            if (enableExit != 0) {
                return enableExit;
            }
            spec.commandLine().getOut().println("Service installed: " + serviceName);
            return 0;
        }
    }

    @Command(name = "uninstall", mixinStandardHelpOptions = true, description = "Uninstall a WIZ systemd service.")
    static class Uninstall implements Callable<Integer> {
        @Spec
        private CommandSpec spec;

        @Parameters(index = "0", description = "Service name.")
        private String name;

        @Option(names = "--dry-run", description = "Print files that would be removed.")
        private boolean dryRun;

        @Option(names = "--systemd-dir", hidden = true)
        private Path systemdDir = DEFAULT_SYSTEMD_DIR;

        @Option(names = "--bin-dir", hidden = true)
        private Path binDir = DEFAULT_BIN_DIR;

        @Option(names = "--systemctl", hidden = true)
        private Path systemctl = Path.of("systemctl");

        @Override
        public Integer call() throws Exception {
            ensureLinux();
            String serviceName = serviceName(name);
            Path commandPath = binDir.resolve(serviceName);
            Path servicePath = systemdDir.resolve(serviceName + ".service");
            if (dryRun) {
                var out = spec.commandLine().getOut();
                out.println(commandPath);
                out.println(servicePath);
                return 0;
            }
            runSystemctl(systemctl, "stop", serviceName);
            runSystemctl(systemctl, "disable", serviceName);
            Files.deleteIfExists(commandPath);
            Files.deleteIfExists(servicePath);
            runSystemctl(systemctl, "daemon-reload");
            spec.commandLine().getOut().println("Service uninstalled: " + serviceName);
            return 0;
        }
    }

    @Command(name = "status", mixinStandardHelpOptions = true, description = "Show WIZ service status.")
    static class Status implements Callable<Integer> {
        @Parameters(index = "0", description = "Service name.")
        private String name;

        public Integer call() throws Exception {
            ensureLinux();
            return runSystemctl("status", serviceName(name));
        }
    }

    @Command(name = "logs", mixinStandardHelpOptions = true,
            description = "Show recent WIZ service output from journald.")
    static class Logs implements Callable<Integer> {
        @Spec
        private CommandSpec spec;

        @Parameters(index = "0", description = "Service name.")
        private String name;

        @Option(names = {"-n", "--lines"}, defaultValue = "200", description = "Number of recent log lines (1-10000).")
        private int lines;

        @Option(names = {"-f", "--follow"}, description = "Continue following new journal entries.")
        private boolean follow;

        @Option(names = "--journalctl", hidden = true)
        private Path journalctl = Path.of("journalctl");

        @Override
        public Integer call() throws Exception {
            ensureLinux();
            if (lines < 1 || lines > 10_000) {
                throw new IllegalArgumentException("Log line count must be between 1 and 10000");
            }
            String normalized = serviceName(name);
            var out = spec.commandLine().getOut();
            out.println("Service: " + normalized);
            out.println("Log source: journald (unit " + normalized + ")");
            out.flush();

            ArrayList<String> args = new ArrayList<>();
            args.add("--unit");
            args.add(normalized);
            args.add("--lines");
            args.add(String.valueOf(lines));
            args.add("--no-pager");
            if (follow) {
                args.add("--follow");
            }
            return runCommand(journalctl, args);
        }
    }

    @Command(name = "start", mixinStandardHelpOptions = true, description = "Start WIZ service(s).")
    static class Start extends ServiceAction {
        Start() {
            super("start");
        }
    }

    @Command(name = "stop", mixinStandardHelpOptions = true, description = "Stop WIZ service(s).")
    static class Stop extends ServiceAction {
        Stop() {
            super("stop");
        }
    }

    @Command(name = "restart", mixinStandardHelpOptions = true, description = "Restart WIZ service(s).")
    static class Restart extends ServiceAction {
        Restart() {
            super("restart");
        }
    }

    static class ServiceAction implements Callable<Integer> {
        private final String action;

        @Parameters(index = "0", arity = "0..1", description = "Service name. If omitted, all wiz.* services are targeted.")
        private String name;

        @Option(names = "--systemd-dir", hidden = true)
        private Path systemdDir = DEFAULT_SYSTEMD_DIR;

        ServiceAction(String action) {
            this.action = action;
        }

        @Override
        public Integer call() throws Exception {
            ensureLinux();
            if (name != null && !name.isBlank()) {
                return runSystemctl(action, serviceName(name));
            }
            int exit = 0;
            for (String service : serviceNames(systemdDir)) {
                exit = Math.max(exit, runSystemctl(action, service));
            }
            return exit;
        }
    }

    private static void ensureLinux() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            throw new IllegalStateException("Service management is only supported on Linux");
        }
    }

    private static List<String> serviceNames(Path systemdDir) throws IOException {
        if (!Files.isDirectory(systemdDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(systemdDir)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("wiz.") && name.endsWith(".service"))
                    .map(name -> name.substring(0, name.length() - ".service".length()))
                    .sorted()
                    .toList();
        }
    }

    private static String serviceName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Service name is required");
        }
        String normalized = requireSingleLine("Service name", name).toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("wiz.")) {
            normalized = "wiz." + normalized;
        }
        if (!normalized.matches("wiz\\.[a-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid service name: " + name);
        }
        String shortName = shortServiceName(normalized);
        if (".".equals(shortName) || "..".equals(shortName)) {
            throw new IllegalArgumentException("Invalid service name: " + name);
        }
        return normalized;
    }

    private static String shortServiceName(String serviceName) {
        return serviceName.startsWith("wiz.") ? serviceName.substring("wiz.".length()) : serviceName;
    }

    private static void validatePort(Integer port) {
        if (port != null && (port < 1 || port > 65_535)) {
            throw new IllegalArgumentException("HTTP port must be between 1 and 65535");
        }
    }

    private static String defaultJavaCommand() {
        Path systemJava = Path.of("/usr/bin/java");
        Path selected = Files.isExecutable(systemJava)
                ? systemJava
                : Path.of(System.getProperty("java.home"), "bin", "java");
        return selected
                .toAbsolutePath()
                .normalize()
                .toString();
    }

    private static Path javaExecutable(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("Java executable is required");
        }
        String value = requireSingleLine("Java executable", command).trim();
        Path configured = Path.of(value);
        if (!configured.isAbsolute()) {
            throw new IllegalArgumentException("Java executable must be an absolute path: " + value);
        }
        Path normalized = configured.normalize();
        if (!Files.isRegularFile(normalized) || !Files.isExecutable(normalized)) {
            throw new IllegalArgumentException("Java executable must be an executable regular file: " + normalized);
        }
        return normalized;
    }

    private static Path serviceRoot(Path configuredRoot, Path configuredBundle) {
        Path selected;
        if (configuredRoot != null) {
            selected = configuredRoot;
        } else if (configuredBundle != null) {
            Path absoluteBundle = configuredBundle.toAbsolutePath().normalize();
            selected = absoluteBundle.getParent() == null ? absoluteBundle : absoluteBundle.getParent();
        } else {
            selected = Path.of(".");
        }
        Path normalized = selected.toAbsolutePath().normalize();
        requireSingleLine("Project root", normalized.toString());
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Project root must already exist as a real directory: " + normalized);
        }
        return normalized;
    }

    private static Path bundlePath(Path projectRoot, Path configuredBundle) throws IOException {
        Path selected = configuredBundle == null
                ? projectRoot.resolve("bundle")
                : configuredBundle.toAbsolutePath().normalize();
        Path normalized = selected.toAbsolutePath().normalize();
        requireSingleLine("Bundle directory", normalized.toString());
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Bundle directory must already exist as a real directory: " + normalized);
        }
        return normalized.toRealPath();
    }

    private static BundleArtifact resolveBundleArtifact(Path bundleRoot, Path configuredArtifact) throws IOException {
        Path manifest = bundleRoot.resolve(BUNDLE_MANIFEST);
        Map<String, Object> manifestValues = readBundleManifest(manifest);
        if (configuredArtifact != null) {
            Path selected = configuredArtifact.isAbsolute()
                    ? configuredArtifact
                    : bundleRoot.resolve(configuredArtifact);
            String type = archiveType(selected);
            Path frontend = manifestFrontendPath(manifestValues, bundleRoot, manifest);
            return checkedBundleArtifact(bundleRoot, selected, type, false, frontend);
        }

        Object rawArtifact = manifestValues.get("artifact");
        if (!(rawArtifact instanceof Map<?, ?> artifactValues)) {
            throw new IllegalArgumentException("Bundle manifest artifact object is required: " + manifest);
        }
        String artifactPath = manifestString(artifactValues, "path", manifest);
        String type = manifestString(artifactValues, "type", manifest).toLowerCase(Locale.ROOT);
        if (!"jar".equals(type) && !"war".equals(type)) {
            throw new IllegalArgumentException("Bundle manifest artifact.type must be 'jar' or 'war': " + type);
        }
        Path relative = Path.of(requireSingleLine("Bundle artifact path", artifactPath));
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("Bundle manifest artifact.path must be relative: " + artifactPath);
        }
        return checkedBundleArtifact(
                bundleRoot,
                bundleRoot.resolve(relative),
                type,
                true,
                manifestFrontendPath(manifestValues, bundleRoot, manifest));
    }

    /** Verifies the complete checksum set required for every 1.0 bundle. */
    private static void verifyBundleChecksums(Path bundleRoot) throws IOException {
        Path checksumFile = bundleRoot.resolve(BUNDLE_CHECKSUMS);
        if (!Files.exists(checksumFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Bundle checksum file is required: " + checksumFile);
        }
        if (!Files.isRegularFile(checksumFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Bundle checksum file must be a regular file: " + checksumFile);
        }

        Path realBundle = bundleRoot.toRealPath();
        LinkedHashMap<Path, String> expected = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(checksumFile, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int lineNumber = index + 1;
            if (line.length() < 67
                    || line.charAt(64) != ' '
                    || line.charAt(65) != ' '
                    || !line.substring(0, 64).matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException(
                        "Invalid bundle checksum entry at " + checksumFile + ":" + lineNumber);
            }

            String pathValue = requireSingleLine("Bundle checksum path", line.substring(66));
            Path relative;
            try {
                relative = Path.of(pathValue);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(
                        "Invalid bundle checksum path at " + checksumFile + ":" + lineNumber, exception);
            }
            String normalizedText = relative.normalize().toString().replace('\\', '/');
            if (pathValue.isBlank()
                    || pathValue.indexOf('\\') >= 0
                    || relative.isAbsolute()
                    || relative.getNameCount() == 0
                    || !relative.normalize().equals(relative)
                    || !normalizedText.equals(pathValue)) {
                throw new IllegalArgumentException(
                        "Bundle checksum path must be a normalized relative path beneath the bundle: " + pathValue);
            }
            if (relative.equals(Path.of(BUNDLE_CHECKSUMS))) {
                throw new IllegalArgumentException(
                        "Bundle checksum file must not include itself: " + BUNDLE_CHECKSUMS);
            }
            if (expected.putIfAbsent(relative, line.substring(0, 64).toLowerCase(Locale.ROOT)) != null) {
                throw new IllegalArgumentException("Duplicate bundle checksum path: " + pathValue);
            }

            Path selected = bundleRoot.resolve(relative).normalize();
            if (!selected.startsWith(bundleRoot)
                    || !Files.isRegularFile(selected, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "Bundle checksum path must reference a regular file beneath the bundle: " + pathValue);
            }
            Path realSelected = selected.toRealPath();
            if (!realSelected.startsWith(realBundle)) {
                throw new IllegalArgumentException(
                        "Bundle checksum path must stay beneath the bundle directory: " + pathValue);
            }
        }

        HashSet<Path> actual = new HashSet<>();
        Files.walkFileTree(bundleRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Path relative = bundleRoot.relativize(file).normalize();
                if (relative.equals(Path.of(BUNDLE_CHECKSUMS))) {
                    return FileVisitResult.CONTINUE;
                }
                if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                    throw new IllegalArgumentException(
                            "Bundle checksum coverage supports only regular files: " + relative);
                }
                Path realFile = file.toRealPath();
                if (!realFile.startsWith(realBundle)) {
                    throw new IllegalArgumentException(
                            "Bundle checksum path must stay beneath the bundle directory: " + relative);
                }
                if (MUTABLE_BUNDLE_FILES.contains(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                actual.add(relative);
                return FileVisitResult.CONTINUE;
            }
        });

        List<String> missing = actual.stream()
                .filter(path -> !expected.containsKey(path))
                .map(path -> path.toString().replace('\\', '/'))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Bundle checksum file is missing entries for: " + String.join(", ", missing));
        }

        for (Map.Entry<Path, String> checksum : expected.entrySet()) {
            Path file = bundleRoot.resolve(checksum.getKey());
            String actualDigest = sha256(file);
            if (!actualDigest.equals(checksum.getValue())) {
                throw new IllegalArgumentException(
                        "Bundle checksum mismatch for " + checksum.getKey().toString().replace('\\', '/'));
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Map<String, Object> readBundleManifest(Path manifest) {
        if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Bundle manifest is required: " + manifest);
        }
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Bundle manifest must be a regular file: " + manifest);
        }
        Map<String, Object> values;
        try {
            values = new ObjectMapper().readValue(Files.readAllBytes(manifest), new TypeReference<>() {
            });
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Invalid bundle manifest " + manifest + ": "
                    + exception.getMessage(), exception);
        }
        if (values == null) {
            throw new IllegalArgumentException("Bundle manifest must contain a JSON object: " + manifest);
        }
        Object schemaVersion = values.get("schemaVersion");
        if (!(schemaVersion instanceof Number number) || Double.compare(number.doubleValue(), 1.0d) != 0) {
            throw new IllegalArgumentException("Unsupported bundle manifest schemaVersion in " + manifest
                    + ": expected 1");
        }
        return values;
    }

    private static String manifestString(Map<?, ?> values, String key, Path manifest) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Bundle manifest artifact." + key + " is required: " + manifest);
        }
        return text.trim();
    }

    private static Path manifestFrontendPath(Map<String, Object> values, Path bundleRoot, Path manifest) {
        Object rawFrontend = values.get("frontend");
        if (rawFrontend == null) {
            throw new IllegalArgumentException("Bundle manifest frontend object is required: " + manifest);
        }
        if (!(rawFrontend instanceof Map<?, ?> frontendValues)) {
            throw new IllegalArgumentException("Bundle manifest frontend object must be a JSON object: " + manifest);
        }
        Object rawPath = frontendValues.get("path");
        if (!(rawPath instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Bundle manifest frontend.path is required: " + manifest);
        }
        String value = requireSingleLine("Bundle frontend path", text.trim());
        Path relative = Path.of(value);
        if (relative.isAbsolute() || relative.getNameCount() == 0 || !relative.normalize().equals(relative)) {
            throw new IllegalArgumentException("Bundle manifest frontend.path must be a normalized relative path: "
                    + value);
        }
        Path selected = bundleRoot.resolve(relative).normalize();
        if (!selected.startsWith(bundleRoot)) {
            throw new IllegalArgumentException("Bundle frontend path must stay beneath the bundle directory: "
                    + selected);
        }
        return selected;
    }

    private static BundleArtifact checkedBundleArtifact(
            Path bundleRoot,
            Path selected,
            String type,
            boolean fromManifest,
            Path frontendCandidate) throws IOException {
        Path normalized = selected.toAbsolutePath().normalize();
        requireSingleLine("Bundle artifact path", normalized.toString());
        if (!normalized.startsWith(bundleRoot)) {
            throw new IllegalArgumentException("Bundle artifact must stay beneath the bundle directory: " + normalized);
        }
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Bundle artifact must be a regular file, not a symlink: " + normalized);
        }
        Path realArtifact = normalized.toRealPath();
        Path realBundle = bundleRoot.toRealPath();
        if (!realArtifact.startsWith(realBundle)) {
            throw new IllegalArgumentException("Bundle artifact must stay beneath the bundle directory: " + normalized);
        }
        String detectedType = archiveType(realArtifact);
        if (!detectedType.equals(type)) {
            String source = fromManifest ? "Bundle manifest artifact.type" : "Bundle artifact type";
            throw new IllegalArgumentException(source + " '" + type + "' does not match " + realArtifact);
        }
        if (!Files.isReadable(realArtifact)) {
            throw new IllegalArgumentException("Bundle artifact must be readable: " + realArtifact);
        }
        Path realFrontend = null;
        if (frontendCandidate != null) {
            if (!Files.isDirectory(frontendCandidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "Bundle frontend path must be a real directory, not a symlink: " + frontendCandidate);
            }
            realFrontend = frontendCandidate.toRealPath();
            if (!realFrontend.startsWith(realBundle)) {
                throw new IllegalArgumentException(
                        "Bundle frontend path must stay beneath the bundle directory: " + frontendCandidate);
            }
        }
        return new BundleArtifact(realBundle, realArtifact, type, realFrontend);
    }

    private static String archiveType(Path artifact) {
        String filename = artifact.getFileName() == null
                ? ""
                : artifact.getFileName().toString().toLowerCase(Locale.ROOT);
        if (filename.endsWith(".jar")) {
            return "jar";
        }
        if (filename.endsWith(".war")) {
            return "war";
        }
        throw new IllegalArgumentException("Bundle artifact must have a .jar or .war extension: " + artifact);
    }

    private static String script(String serviceName, Path javaCommand, Path root,
            BundleArtifact bundleArtifact, Integer port, String profiles) {
        String safeServiceName = requireSingleLine("Service name", serviceName);
        String safeCommand = requireSingleLine("Java executable", javaCommand.toString());
        String rootValue = requireSingleLine("Workspace root", root.toString());
        String bundleValue = requireSingleLine("Bundle directory", bundleArtifact.bundleRoot().toString());
        String artifactValue = requireSingleLine("Bundle artifact", bundleArtifact.artifact().toString());
        String profileValue = normalizeProfiles(profiles);
        return "#!/bin/bash\n"
                + metadataLine("name", shortServiceName(safeServiceName))
                + metadataLine("root", rootValue)
                + metadataLine("port", port == null ? "config" : String.valueOf(port))
                + metadataLine("bundle", bundleValue)
                + metadataLine("artifact", artifactValue)
                + metadataLine("artifact-type", bundleArtifact.type())
                + metadataLine("profiles", profileValue)
                + metadataLine("logs", "journald")
                + metadataLine("command", safeCommand)
                + "set -euo pipefail\n"
                + "cd " + shell(bundleValue) + "\n"
                + "exec " + shell(safeCommand) + " -jar " + shell(artifactValue)
                + " " + shell("--spring.profiles.active=" + profileValue)
                + (port == null ? "" : " " + shell("--server.port=" + port))
                + "\n";
    }

    private static String unit(String serviceName, Path commandPath, String user) {
        String safeServiceName = requireSingleLine("Service name", serviceName);
        String safeUser = systemdUser(user);
        String execStart = requireSystemdExecutablePath(commandPath);
        return "[Unit]\n"
                + "Description=" + safeServiceName + "\n"
                + "Wants=network-online.target\n"
                + "After=network-online.target\n\n"
                + "[Service]\n"
                + "Type=simple\n"
                + "User=" + safeUser + "\n"
                + "Environment=\"PATH=/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin\"\n"
                + "ExecStart=" + execStart + "\n"
                + "StandardOutput=journal\n"
                + "StandardError=journal\n"
                + "SyslogIdentifier=" + safeServiceName + "\n"
                + "Restart=on-failure\n"
                + "RestartSec=5s\n"
                + "TimeoutStopSec=30s\n"
                + "SuccessExitStatus=143\n"
                + "UMask=0027\n\n"
                + "[Install]\n"
                + "WantedBy=multi-user.target\n";
    }

    private static String serviceUser(String configured, Path workspaceRoot, boolean allowRoot) throws IOException {
        String selected = configured == null || configured.isBlank()
                ? Files.getOwner(workspaceRoot).getName()
                : configured.trim();
        String normalized = systemdUser(selected);
        if (isRootIdentity(normalized) && !allowRoot) {
            throw new IllegalArgumentException("Refusing to run service as root. Set --user to a non-root account, "
                    + "or pass --allow-root to explicitly approve root execution.");
        }
        try {
            workspaceRoot.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(normalized);
        } catch (UserPrincipalNotFoundException exception) {
            throw new IllegalArgumentException("Service user does not exist: " + normalized, exception);
        }
        return normalized;
    }

    private static boolean isRootIdentity(String user) {
        return "root".equalsIgnoreCase(user) || "0".equals(user);
    }

    /**
     * Verifies the access that the generated systemd unit will need after it
     * drops privileges. POSIX mode bits are treated conservatively: an ACL may
     * grant more access, but never causes a missing owner/group/other bit to be
     * accepted. A second probe under the effective service identity catches ACL
     * restrictions that are not exposed through Java's POSIX attribute view.
     *
     * <p>Non-POSIX file systems are rejected for non-root services because this
     * command cannot make an equivalent, fail-closed access determination there.
     * Root remains compatible with the explicit {@code --allow-root} flow and is
     * not subject to discretionary POSIX read/search checks.</p>
     */
    private static void requireBundleAccessibleToServiceUser(
            BundleArtifact bundleArtifact,
            Path javaExecutable,
            String serviceUser) throws IOException, InterruptedException {
        PosixIdentity identity = posixIdentity(serviceUser);
        if (identity.uid() == 0L) {
            return;
        }

        requireEffectiveAccess(
                serviceUser, identity, javaExecutable, "-x", "execute Java runtime");

        Path artifact = bundleArtifact.artifact();
        ArrayList<Path> ancestors = new ArrayList<>();
        for (Path directory = artifact.getParent(); directory != null; directory = directory.getParent()) {
            ancestors.add(directory);
        }
        Collections.reverse(ancestors);

        for (Path directory : ancestors) {
            PosixAccessAttributes attributes = posixAccessAttributes(directory);
            if (!grants(identity, attributes, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE)) {
                throw inaccessibleServicePath(serviceUser, "traverse directory required to reach bundle artifact",
                        directory);
            }
            requireEffectiveAccess(serviceUser, identity, directory, "-x",
                    "traverse directory required to reach bundle artifact");
        }

        PosixAccessAttributes artifactAttributes = posixAccessAttributes(artifact);
        if (!grants(identity, artifactAttributes, PosixFilePermission.OWNER_READ,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ)) {
            throw inaccessibleServicePath(serviceUser, "read bundle artifact", artifact);
        }
        requireEffectiveAccess(serviceUser, identity, artifact, "-r", "read bundle artifact");

        Path configRoot = bundleArtifact.bundleRoot().resolve("config");
        if (Files.exists(configRoot, LinkOption.NOFOLLOW_LINKS)) {
            requireReadableRuntimeTree(serviceUser, identity, configRoot, "bundle configuration");
        }
        if (bundleArtifact.frontendRoot() != null) {
            requireReadableRuntimeTree(
                    serviceUser, identity, bundleArtifact.frontendRoot(), "bundle frontend");
        }
    }

    private static void requireReadableRuntimeTree(
            String serviceUser,
            PosixIdentity identity,
            Path root,
            String label) throws IOException, InterruptedException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(label + " must be a real directory, not a symlink: " + root);
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    if (attributes.isSymbolicLink()) {
                        throw new IllegalArgumentException(label + " must not contain symbolic links: " + directory);
                    }
                    try {
                        requirePosixPermission(
                                serviceUser,
                                identity,
                                directory,
                                PosixFilePermission.OWNER_EXECUTE,
                                PosixFilePermission.GROUP_EXECUTE,
                                PosixFilePermission.OTHERS_EXECUTE,
                                "traverse " + label + " directory");
                        requirePosixPermission(
                                serviceUser,
                                identity,
                                directory,
                                PosixFilePermission.OWNER_READ,
                                PosixFilePermission.GROUP_READ,
                                PosixFilePermission.OTHERS_READ,
                                "read " + label + " directory");
                        requireEffectiveAccess(
                                serviceUser, identity, directory, "-x", "traverse " + label + " directory");
                        requireEffectiveAccess(
                                serviceUser, identity, directory, "-r", "read " + label + " directory");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new ServiceAccessInterruptedException(exception);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                        throw new IllegalArgumentException(label + " must contain only regular files: " + file);
                    }
                    requirePosixPermission(
                            serviceUser,
                            identity,
                            file,
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.OTHERS_READ,
                            "read " + label + " file");
                    try {
                        requireEffectiveAccess(
                                serviceUser, identity, file, "-r", "read " + label + " file");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new ServiceAccessInterruptedException(exception);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (ServiceAccessInterruptedException exception) {
            throw exception.interruptedException();
        }
    }

    private static void requirePosixPermission(
            String serviceUser,
            PosixIdentity identity,
            Path path,
            PosixFilePermission ownerPermission,
            PosixFilePermission groupPermission,
            PosixFilePermission otherPermission,
            String action) throws IOException {
        PosixAccessAttributes attributes = posixAccessAttributes(path);
        if (!grants(identity, attributes, ownerPermission, groupPermission, otherPermission)) {
            throw inaccessibleServicePath(serviceUser, action, path);
        }
    }

    private static PosixAccessAttributes posixAccessAttributes(Path path) throws IOException {
        var store = Files.getFileStore(path);
        if (!store.supportsFileAttributeView("posix") || !store.supportsFileAttributeView("unix")) {
            throw new IllegalArgumentException("Cannot safely verify service-user access on a non-POSIX file system: "
                    + path);
        }
        if (Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
            throw new IllegalArgumentException("Cannot safely verify service-user access using POSIX mode bits on "
                    + "a file system that exposes a separate ACL model: " + path);
        }
        PosixFileAttributes posix = Files.readAttributes(
                path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Map<String, Object> unix = Files.readAttributes(path, "unix:uid,gid", LinkOption.NOFOLLOW_LINKS);
        return new PosixAccessAttributes(
                unixId(unix.get("uid"), "uid", path),
                unixId(unix.get("gid"), "gid", path),
                posix.permissions());
    }

    private static long unixId(Object raw, String name, Path path) {
        if (raw instanceof Integer value) {
            return Integer.toUnsignedLong(value);
        }
        if (raw instanceof Long value && value >= 0L) {
            return value;
        }
        throw new IllegalArgumentException("Cannot safely determine POSIX " + name + " for " + path);
    }

    private static boolean grants(PosixIdentity identity, PosixAccessAttributes attributes,
            PosixFilePermission ownerPermission, PosixFilePermission groupPermission,
            PosixFilePermission otherPermission) {
        if (identity.uid() == attributes.uid()) {
            return attributes.permissions().contains(ownerPermission);
        }
        if (identity.gids().contains(attributes.gid())) {
            return attributes.permissions().contains(groupPermission);
        }
        return attributes.permissions().contains(otherPermission);
    }

    private static PosixIdentity posixIdentity(String user) throws IOException, InterruptedException {
        Path id = requiredExecutable("POSIX identity lookup", Path.of("/usr/bin/id"), Path.of("/bin/id"));
        long uid = parseUnixId(runCaptured(id, List.of("-u", "--", user)), "uid", user);
        String groupOutput = runCaptured(id, List.of("-G", "--", user));
        HashSet<Long> gids = new HashSet<>();
        for (String value : groupOutput.trim().split("\\s+")) {
            if (!value.isBlank()) {
                gids.add(parseUnixId(value, "group id", user));
            }
        }
        if (gids.isEmpty()) {
            throw new IllegalArgumentException("Cannot safely determine POSIX groups for service user: " + user);
        }
        return new PosixIdentity(uid, Set.copyOf(gids));
    }

    private static long parseUnixId(String value, String label, String user) {
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed < 0L || parsed > 0xffff_ffffL) {
                throw new NumberFormatException("outside unsigned 32-bit range");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Cannot safely determine POSIX " + label
                    + " for service user " + user + ": " + value.trim(), exception);
        }
    }

    private static String runCaptured(Path command, List<String> args) throws IOException, InterruptedException {
        ArrayList<String> argv = new ArrayList<>();
        argv.add(command.toString());
        argv.addAll(args);
        Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalArgumentException("Command failed while verifying service-user access ("
                    + command + ", exit " + exit + "): " + output);
        }
        return output;
    }

    private static void requireEffectiveAccess(String serviceUser, PosixIdentity identity, Path path,
            String testOperator, String action) throws IOException, InterruptedException {
        Path test = requiredExecutable("POSIX access probe", Path.of("/usr/bin/test"), Path.of("/bin/test"));
        PosixIdentity current = currentPosixIdentity();
        ArrayList<String> argv = new ArrayList<>();
        if (current.uid() == identity.uid()) {
            argv.add(test.toString());
        } else if (current.uid() == 0L) {
            Path runuser = requiredExecutable(
                    "service-user ACL access probe", Path.of("/usr/sbin/runuser"), Path.of("/sbin/runuser"));
            argv.add(runuser.toString());
            argv.add("--user");
            argv.add(serviceUser);
            argv.add("--");
            argv.add(test.toString());
        } else {
            throw new IllegalArgumentException("Cannot safely verify ACL-effective bundle access for service user '"
                    + serviceUser + "' while running as a different non-root account");
        }
        argv.add(testOperator);
        argv.add(path.toString());
        int exit = new ProcessBuilder(argv).redirectErrorStream(true).start().waitFor();
        if (exit != 0) {
            throw inaccessibleServicePath(serviceUser, action, path);
        }
    }

    private static PosixIdentity currentPosixIdentity() throws IOException, InterruptedException {
        Path id = requiredExecutable("current POSIX identity lookup", Path.of("/usr/bin/id"), Path.of("/bin/id"));
        long uid = parseUnixId(runCaptured(id, List.of("-u")), "uid", "current process");
        String groupOutput = runCaptured(id, List.of("-G"));
        HashSet<Long> gids = new HashSet<>();
        for (String value : groupOutput.trim().split("\\s+")) {
            if (!value.isBlank()) {
                gids.add(parseUnixId(value, "group id", "current process"));
            }
        }
        return new PosixIdentity(uid, Set.copyOf(gids));
    }

    private static Path requiredExecutable(String purpose, Path... candidates) {
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Cannot perform " + purpose
                + ": required operating-system executable is unavailable");
    }

    private static IllegalArgumentException inaccessibleServicePath(String serviceUser, String action, Path path) {
        return new IllegalArgumentException("Service user '" + serviceUser + "' cannot " + action + ": " + path);
    }

    private static String normalizeProfiles(String configured) {
        String value = requireSingleLine("Spring profiles", configured).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]*(?:,[A-Za-z0-9][A-Za-z0-9._-]*)*")) {
            throw new IllegalArgumentException("Spring profiles must be a comma-separated list of safe profile names");
        }
        return value;
    }

    private static String systemdUser(String user) {
        String value = requireSingleLine("Service user", user).trim();
        if (!value.matches("(?:[A-Za-z_][A-Za-z0-9_.-]*|[0-9]+)")) {
            throw new IllegalArgumentException("Service user is not a supported systemd identity: " + user);
        }
        return value;
    }

    private static int runSystemctl(String... args) throws IOException, InterruptedException {
        return runSystemctl(Path.of("systemctl"), args);
    }

    private static int runSystemctl(Path systemctl, String... args) throws IOException, InterruptedException {
        return runCommand(systemctl, List.of(args));
    }

    private static int runCommand(Path command, List<String> args) throws IOException, InterruptedException {
        ArrayList<String> argv = new ArrayList<>();
        argv.add(requireSingleLine("Command path", command.toString()));
        argv.addAll(args);
        Process process = new ProcessBuilder(argv).inheritIO().start();
        return process.waitFor();
    }

    private static String shell(String value) {
        return "'" + requireSingleLine("Shell value", value).replace("'", "'\"'\"'") + "'";
    }

    private static String metadataLine(String key, String value) {
        if (key == null || !key.matches("[a-z][a-z0-9.-]*")) {
            throw new IllegalArgumentException("Invalid service metadata key");
        }
        return "# wiz.service." + key + "="
                + requireSingleLine("Service metadata '" + key + "'", value)
                + "\n";
    }

    private static String requireSingleLine(String label, String value) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        if (!isSafeSingleLine(value)) {
            throw new IllegalArgumentException(label + " must be a single line without control characters");
        }
        return value;
    }

    private static boolean isSafeSingleLine(String value) {
        return value.codePoints().noneMatch(codePoint -> Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.LINE_SEPARATOR
                || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR);
    }

    private static String requireSystemdExecutablePath(Path path) {
        String value = requireSingleLine("Service executable path", path == null ? null : path.toString());
        if (!path.isAbsolute() || !value.matches("/[A-Za-z0-9_./+~-]+")) {
            throw new IllegalArgumentException("Service executable path must be an absolute path without whitespace or systemd expansion characters");
        }
        return value;
    }

    static List<ServiceDescriptor> describeServices(Path systemdDir, Path binDir) throws IOException {
        if (!Files.isDirectory(systemdDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(systemdDir)) {
            return files
                    .filter(path -> {
                        String filename = path.getFileName().toString();
                        return filename.startsWith("wiz.") && filename.endsWith(".service");
                    })
                    .sorted()
                    .map(path -> serviceDescriptor(path, binDir))
                    .toList();
        }
    }

    private static ServiceDescriptor serviceDescriptor(Path servicePath, Path binDir) {
        String filename = servicePath.getFileName().toString();
        String serviceName = filename.substring(0, filename.length() - ".service".length());
        String name = shortServiceName(serviceName);
        Path binary = binDir.resolve(serviceName);
        Map<String, String> metadata = metadata(binary);
        String root = metadata.getOrDefault("root", "-");
        String port = metadata.getOrDefault("port", "config");
        String log = metadata.getOrDefault("logs", "journald");
        return new ServiceDescriptor(name, servicePath, binary, root, port, log);
    }

    private static Map<String, String> metadata(Path binary) {
        if (!Files.isRegularFile(binary)) {
            return Map.of();
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(binary, StandardCharsets.UTF_8)) {
                if (!line.startsWith("# wiz.service.")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= "# wiz.service.".length()) {
                    continue;
                }
                String key = line.substring("# wiz.service.".length(), separator).trim();
                String value = line.substring(separator + 1).trim();
                if (!key.isBlank() && !value.isBlank() && isSafeSingleLine(key) && isSafeSingleLine(value)) {
                    values.put(key, value);
                }
            }
        } catch (IOException exception) {
            return Map.of();
        }
        return values;
    }

    private static void printServices(List<ServiceDescriptor> services, PrintWriter out) {
        if (services.isEmpty()) {
            out.println("(no WIZ services found)");
            return;
        }
        ArrayList<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"name", "systemd", "binary", "root", "port", "logs"});
        for (ServiceDescriptor service : services) {
            rows.add(new String[] {
                    service.name(),
                    service.systemd().toString(),
                    service.binary().toString(),
                    service.root(),
                    service.port(),
                    service.log()
            });
        }
        int[] widths = new int[rows.get(0).length];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i].length());
            }
        }
        String border = tableBorder(widths);
        out.println(border);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            out.println(tableRow(rows.get(rowIndex), widths));
            if (rowIndex == 0) {
                out.println(border);
            }
        }
        out.println(border);
    }

    private static String tableBorder(int[] widths) {
        StringBuilder line = new StringBuilder("+");
        for (int width : widths) {
            line.append("-".repeat(width + 2)).append("+");
        }
        return line.toString();
    }

    private static String tableRow(String[] row, int[] widths) {
        StringBuilder line = new StringBuilder("|");
        for (int i = 0; i < row.length; i++) {
            line.append(' ').append(pad(row[i], widths[i])).append(" |");
        }
        return line.toString();
    }

    private static String pad(String value, int width) {
        return value + " ".repeat(Math.max(0, width - value.length()));
    }

    record BundleArtifact(Path bundleRoot, Path artifact, String type, Path frontendRoot) {
    }

    private static final class ServiceAccessInterruptedException extends IOException {
        private final InterruptedException interruptedException;

        private ServiceAccessInterruptedException(InterruptedException interruptedException) {
            super(interruptedException);
            this.interruptedException = interruptedException;
        }

        private InterruptedException interruptedException() {
            return interruptedException;
        }
    }

    record PosixIdentity(long uid, Set<Long> gids) {
    }

    record PosixAccessAttributes(long uid, long gid, Set<PosixFilePermission> permissions) {
    }

    record ServiceDescriptor(String name, Path systemd, Path binary, String root, String port, String log) {
    }
}
