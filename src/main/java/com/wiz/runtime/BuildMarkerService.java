package com.wiz.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        if (dependencySummary != null) {
            marker.put("dependencyManifest", dependencySummary.manifestPath());
            marker.put("dependencyDigest", Map.of(
                    "algorithm", dependencySummary.digestAlgorithm(),
                    "value", dependencySummary.digest()));
            marker.put("dependencyCount", dependencySummary.dependencyCount());
            marker.put("cycloneDxBom", dependencySummary.cycloneDxBomPath());
        }
        Files.writeString(project.bundleRoot().resolve(MARKER_FILE), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(marker) + "\n");
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
