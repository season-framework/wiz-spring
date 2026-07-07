package com.wiz.build;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import com.wiz.runtime.ProjectContext;

import tools.jackson.databind.ObjectMapper;

public class SupplyChainManifestService {

    public static final String DEPENDENCY_MANIFEST_FILE = ".wiz-dependencies.json";
    public static final String CYCLONEDX_BOM_FILE = "bom.json";

    private static final String DIGEST_ALGORITHM = "SHA-256";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Result write(ProjectContext project, Instant generatedAt) throws IOException {
        Instant timestamp = generatedAt == null ? Instant.now() : generatedAt;
        Files.createDirectories(project.bundleRoot());
        Files.createDirectories(ProjectBuildLayout.targetRoot(project));

        List<Artifact> dependencies = dependencyArtifacts(project);
        List<Artifact> projectArtifacts = projectArtifacts(project);
        List<Artifact> buildInputs = buildInputs(project);
        String dependencyDigest = dependencyDigest(dependencies);

        Path manifest = project.bundleRoot().resolve(DEPENDENCY_MANIFEST_FILE);
        Files.writeString(manifest, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                manifest(project, timestamp, dependencyDigest, dependencies, projectArtifacts, buildInputs)) + "\n");

        Path bom = ProjectBuildLayout.cyclonedxBom(project);
        Files.writeString(bom, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                cyclonedxBom(project, timestamp, dependencies)) + "\n");

        return new Result(manifest, bom, DIGEST_ALGORITHM, dependencyDigest, dependencies.size());
    }

    private LinkedHashMap<String, Object> manifest(
            ProjectContext project,
            Instant generatedAt,
            String dependencyDigest,
            List<Artifact> dependencies,
            List<Artifact> projectArtifacts,
            List<Artifact> buildInputs) {
        LinkedHashMap<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("workspaceRoot", project.root().toAbsolutePath().normalize().toString());
        manifest.put("javaPackageRoot", project.packageRoot());
        manifest.put("runtimeVersion", runtimeVersion());
        manifest.put("javaVersion", System.getProperty("java.version"));
        manifest.put("generatedAt", generatedAt.toString());
        manifest.put("dependencyDigest", digestMap(dependencyDigest));
        manifest.put("dependencyCount", dependencies.size());
        manifest.put("dependencies", artifactMaps(dependencies));
        manifest.put("projectArtifacts", artifactMaps(projectArtifacts));
        manifest.put("buildInputs", artifactMaps(buildInputs));
        manifest.put("cycloneDxBom", "target/" + CYCLONEDX_BOM_FILE);
        return manifest;
    }

    private LinkedHashMap<String, Object> cyclonedxBom(ProjectContext project, Instant generatedAt, List<Artifact> dependencies) {
        LinkedHashMap<String, Object> bom = new LinkedHashMap<>();
        bom.put("bomFormat", "CycloneDX");
        bom.put("specVersion", "1.5");
        bom.put("version", 1);
        bom.put("metadata", cyclonedxMetadata(project, generatedAt));
        bom.put("components", dependencies.stream().map(this::cyclonedxComponent).toList());
        return bom;
    }

    private LinkedHashMap<String, Object> cyclonedxMetadata(ProjectContext project, Instant generatedAt) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("timestamp", generatedAt.toString());
        LinkedHashMap<String, Object> component = new LinkedHashMap<>();
        component.put("type", "application");
        component.put("name", "wiz-app");
        component.put("version", runtimeVersion());
        metadata.put("component", component);

        LinkedHashMap<String, Object> toolComponent = new LinkedHashMap<>();
        toolComponent.put("type", "application");
        toolComponent.put("name", "wiz-spring");
        toolComponent.put("version", runtimeVersion());
        metadata.put("tools", java.util.Map.of("components", List.of(toolComponent)));
        return metadata;
    }

    private LinkedHashMap<String, Object> cyclonedxComponent(Artifact artifact) {
        LinkedHashMap<String, Object> component = new LinkedHashMap<>();
        component.put("type", "library");
        component.put("name", artifact.coordinates()
                .map(MavenCoordinates::artifactId)
                .orElseGet(() -> Path.of(artifact.path()).getFileName().toString()));
        artifact.coordinates().map(MavenCoordinates::groupId).ifPresent(group -> component.put("group", group));
        artifact.coordinates().map(MavenCoordinates::version).ifPresent(version -> component.put("version", version));
        artifact.coordinates().map(MavenCoordinates::purl).ifPresent(purl -> component.put("purl", purl));
        component.put("scope", "required");
        component.put("hashes", List.of(java.util.Map.of("alg", DIGEST_ALGORITHM, "content", artifact.sha256())));
        component.put("properties", List.of(
                java.util.Map.of("name", "wiz:path", "value", artifact.path()),
                java.util.Map.of("name", "wiz:artifactType", "value", artifact.type())));
        return component;
    }

    private List<Artifact> dependencyArtifacts(ProjectContext project) throws IOException {
        return jarArtifacts(project.bundleRoot().resolve("lib"), project.bundleRoot(), "runtime-dependency");
    }

    private List<Artifact> projectArtifacts(ProjectContext project) throws IOException {
        ArrayList<Artifact> artifacts = new ArrayList<>();
        Path projectApi = project.bundleRoot().resolve("app-api.jar");
        if (Files.isRegularFile(projectApi)) {
            artifacts.add(artifact(projectApi, project.bundleRoot(), "app-api"));
        }
        return List.copyOf(artifacts);
    }

    private List<Artifact> buildInputs(ProjectContext project) throws IOException {
        ArrayList<Artifact> artifacts = new ArrayList<>();
        Path pom = project.bundleRoot().resolve("pom.xml");
        if (Files.isRegularFile(pom)) {
            artifacts.add(artifact(pom, project.bundleRoot(), "maven-pom"));
        }
        return List.copyOf(artifacts);
    }

    private List<Artifact> jarArtifacts(Path directory, Path relativeRoot, String type) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        ArrayList<Artifact> artifacts = new ArrayList<>();
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path jar : paths
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                artifacts.add(artifact(jar, relativeRoot, type));
            }
        }
        return List.copyOf(artifacts);
    }

    private Artifact artifact(Path path, Path relativeRoot, String type) throws IOException {
        return new Artifact(relativePath(relativeRoot, path), type, Files.size(path), sha256(path), mavenCoordinates(path));
    }

    private String dependencyDigest(List<Artifact> artifacts) {
        MessageDigest digest = digest();
        for (Artifact artifact : artifacts) {
            update(digest, artifact.path());
            update(digest, artifact.sha256());
            artifact.coordinates().ifPresent(coordinates -> {
                update(digest, coordinates.groupId());
                update(digest, coordinates.artifactId());
                update(digest, coordinates.version());
            });
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private LinkedHashMap<String, Object> digestMap(String digest) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("algorithm", DIGEST_ALGORITHM);
        value.put("value", digest);
        return value;
    }

    private List<LinkedHashMap<String, Object>> artifactMaps(List<Artifact> artifacts) {
        return artifacts.stream().map(this::artifactMap).toList();
    }

    private LinkedHashMap<String, Object> artifactMap(Artifact artifact) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("path", artifact.path());
        value.put("type", artifact.type());
        value.put("size", artifact.size());
        value.put("sha256", artifact.sha256());
        artifact.coordinates().ifPresent(coordinates -> {
            value.put("groupId", coordinates.groupId());
            value.put("artifactId", coordinates.artifactId());
            value.put("version", coordinates.version());
            value.put("purl", coordinates.purl());
        });
        return value;
    }

    private Optional<MavenCoordinates> mavenCoordinates(Path path) {
        if (!path.getFileName().toString().endsWith(".jar")) {
            return Optional.empty();
        }
        try (JarFile jar = new JarFile(path.toFile())) {
            Optional<JarEntry> entry = jar.stream()
                    .filter(item -> !item.isDirectory())
                    .filter(item -> item.getName().startsWith("META-INF/maven/"))
                    .filter(item -> item.getName().endsWith("/pom.properties"))
                    .sorted(Comparator.comparing(JarEntry::getName))
                    .findFirst();
            if (entry.isEmpty()) {
                return Optional.empty();
            }
            Properties properties = new Properties();
            try (InputStream input = jar.getInputStream(entry.get())) {
                properties.load(input);
            }
            String groupId = property(properties, "groupId");
            String artifactId = property(properties, "artifactId");
            String version = property(properties, "version");
            if (groupId.isBlank() || artifactId.isBlank() || version.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new MavenCoordinates(groupId, artifactId, version));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private String property(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value == null ? "" : value.trim();
    }

    private String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(DIGEST_ALGORITHM + " is not available", exception);
        }
    }

    private void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private String relativePath(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (normalizedPath.startsWith(normalizedRoot)) {
            return normalizedRoot.relativize(normalizedPath).toString().replace('\\', '/');
        }
        return normalizedPath.toString().replace('\\', '/');
    }

    private String runtimeVersion() {
        String version = SupplyChainManifestService.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "dev" : version;
    }

    public record Result(Path manifest, Path cycloneDxBom, String digestAlgorithm, String dependencyDigest, int dependencyCount) {
    }

    private record Artifact(String path, String type, long size, String sha256, Optional<MavenCoordinates> coordinates) {
    }

    private record MavenCoordinates(String groupId, String artifactId, String version) {
        String purl() {
            return "pkg:maven/" + groupId + "/" + artifactId + "@" + version;
        }
    }
}
