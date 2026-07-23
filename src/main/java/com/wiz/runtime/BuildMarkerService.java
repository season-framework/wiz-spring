package com.wiz.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public class BuildMarkerService {

    public static final String MARKER_FILE = ".wiz-build.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void write(ProjectContext project, List<String> phases, String frontendMode, Instant startedAt, Instant finishedAt) throws IOException {
        write(project, phases, frontendMode, startedAt, finishedAt, null);
    }

    public void write(ProjectContext project, List<String> phases, String frontendMode, Instant startedAt, Instant finishedAt, DependencySummary dependencySummary) throws IOException {
        Files.createDirectories(project.bundleRoot());
        LinkedHashMap<String, Object> marker = new LinkedHashMap<>();
        marker.put("workspaceRoot", project.root().toAbsolutePath().normalize().toString());
        marker.put("javaPackageRoot", project.packageRoot());
        marker.put("buildPhases", List.copyOf(phases));
        marker.put("runtimeVersion", WizSpringVersion.current());
        marker.put("javaVersion", System.getProperty("java.version"));
        marker.put("buildStartedAt", startedAt.toString());
        marker.put("buildFinishedAt", finishedAt.toString());
        marker.put("frontendMode", frontendMode);
        marker.put("bundleArtifactMtime", bundleArtifactMtime(project.bundleRoot()));
        marker.put("runtimeDigest", Map.of(
                "algorithm", "SHA-256",
                "value", runtimeDigest(project, dependencySummary)));
        if (dependencySummary != null) {
            marker.put("dependencyManifest", dependencySummary.manifestPath());
            marker.put("dependencyDigest", Map.of(
                    "algorithm", dependencySummary.digestAlgorithm(),
                    "value", dependencySummary.digest()));
            marker.put("dependencyCount", dependencySummary.dependencyCount());
            marker.put("cycloneDxBom", dependencySummary.cycloneDxBomPath());
        }
        Path target = project.bundleRoot().resolve(MARKER_FILE);
        Path temporary = project.bundleRoot().resolve(MARKER_FILE + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(marker) + "\n");
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Optional<Map<String, Object>> read(ProjectContext project) {
        Path marker = project.bundleRoot().resolve(MARKER_FILE);
        if (!Files.isRegularFile(marker)) {
            return Optional.empty();
        }
        try {
            Map<String, Object> value = objectMapper.readValue(Files.readAllBytes(marker), new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return Optional.of(Map.copyOf(value));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    public Optional<String> debugHeader(ProjectContext project) {
        return read(project).map(marker -> "package=" + string(marker, "javaPackageRoot")
                + ";frontend=" + string(marker, "frontendMode")
                + ";finished=" + string(marker, "buildFinishedAt"));
    }

    private long bundleArtifactMtime(Path bundleRoot) throws IOException {
        if (!Files.isDirectory(bundleRoot)) {
            return 0L;
        }
        try (Stream<Path> paths = Files.walk(bundleRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals(MARKER_FILE))
                    .mapToLong(this::modifiedTime)
                    .max()
                    .orElse(0L);
        }
    }

    private String runtimeDigest(ProjectContext project, DependencySummary dependencySummary) throws IOException {
        MessageDigest digest = sha256();
        update(digest, "wiz-runtime-input-v1");
        update(digest, project.packageRoot());
        update(digest, WizSpringVersion.current());
        if (dependencySummary == null) {
            update(digest, "dependency:none");
        } else {
            update(digest, dependencySummary.digestAlgorithm());
            update(digest, dependencySummary.digest());
            update(digest, Integer.toString(dependencySummary.dependencyCount()));
        }
        addPath(digest, project.bundleRoot(), project.bundleRoot().resolve("app-api.jar"), path -> true);
        addPath(digest, project.bundleRoot(), project.bundleRoot().resolve("classes"), path -> true);
        addPath(digest, project.bundleRoot(), project.bundleRoot().resolve("config"), path -> true);
        addPath(digest, project.bundleRoot(), project.bundleRoot().resolve("src/app"),
                path -> path.getFileName().toString().equals("app.json"));
        addPath(digest, project.bundleRoot(), project.bundleRoot().resolve("src/route"),
                path -> path.getFileName().toString().equals("app.json"));
        return HexFormat.of().formatHex(digest.digest());
    }

    private void addPath(
            MessageDigest digest,
            Path relativeRoot,
            Path input,
            java.util.function.Predicate<Path> include) throws IOException {
        update(digest, relativeRoot.relativize(input).toString().replace('\\', '/'));
        if (Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            addFile(digest, relativeRoot, input);
            return;
        }
        if (!Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS)) {
            update(digest, "missing");
            return;
        }
        try (Stream<Path> paths = Files.walk(input)) {
            for (Path file : paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(include)
                    .sorted(Comparator.comparing(path -> relativeRoot.relativize(path).toString()))
                    .toList()) {
                addFile(digest, relativeRoot, file);
            }
        }
    }

    private void addFile(MessageDigest digest, Path relativeRoot, Path file) throws IOException {
        update(digest, relativeRoot.relativize(file).toString().replace('\\', '/'));
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        digest.update((byte) 0);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void update(MessageDigest digest, String value) {
        digest.update(String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private long modifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    private String string(Map<String, Object> marker, String key) {
        Object value = marker.get(key);
        return value == null ? "" : value.toString();
    }

    public record DependencySummary(String manifestPath, String digestAlgorithm, String digest, int dependencyCount, String cycloneDxBomPath) {
    }
}
