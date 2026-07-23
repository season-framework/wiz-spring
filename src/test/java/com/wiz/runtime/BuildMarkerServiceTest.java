package com.wiz.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildMarkerServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void runtimeDigestIgnoresFrontendOutputAndBuildTimestamps() throws Exception {
        ProjectContext project = project();
        BuildMarkerService service = new BuildMarkerService();
        BuildMarkerService.DependencySummary dependencies = dependencies("a".repeat(64));

        service.write(project, List.of("bundle"), "real", Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                dependencies);
        String first = runtimeDigest(service, project);
        Files.writeString(project.bundleRoot().resolve("www/index.html"), "<html>changed</html>\n");
        service.write(project, List.of("bundle"), "real", Instant.EPOCH.plusSeconds(2),
                Instant.EPOCH.plusSeconds(3), dependencies);

        assertEquals(first, runtimeDigest(service, project));
    }

    @Test
    void runtimeDigestChangesWithJavaMetadataConfigAndDependencies() throws Exception {
        ProjectContext project = project();
        BuildMarkerService service = new BuildMarkerService();
        BuildMarkerService.DependencySummary dependencies = dependencies("a".repeat(64));
        service.write(project, List.of("bundle"), "real", Instant.EPOCH, Instant.EPOCH, dependencies);
        String initial = runtimeDigest(service, project);

        Files.writeString(project.bundleRoot().resolve("app-api.jar"), "java-v2");
        service.write(project, List.of("bundle"), "real", Instant.EPOCH, Instant.EPOCH, dependencies);
        String javaChanged = runtimeDigest(service, project);
        assertNotEquals(initial, javaChanged);

        Files.writeString(project.bundleRoot().resolve("src/app/page.test/app.json"), "{\"id\":\"changed\"}\n");
        service.write(project, List.of("bundle"), "real", Instant.EPOCH, Instant.EPOCH, dependencies);
        String metadataChanged = runtimeDigest(service, project);
        assertNotEquals(javaChanged, metadataChanged);

        Files.writeString(project.bundleRoot().resolve("config/application.yml"), "review: changed\n");
        service.write(project, List.of("bundle"), "real", Instant.EPOCH, Instant.EPOCH, dependencies);
        String configChanged = runtimeDigest(service, project);
        assertNotEquals(metadataChanged, configChanged);

        service.write(project, List.of("bundle"), "real", Instant.EPOCH, Instant.EPOCH,
                dependencies("b".repeat(64)));
        assertNotEquals(configChanged, runtimeDigest(service, project));
    }

    private ProjectContext project() throws Exception {
        Path root = tempDir.resolve("workspace-" + java.util.UUID.randomUUID());
        Path bundle = root.resolve("bundle");
        Files.createDirectories(bundle.resolve("classes"));
        Files.createDirectories(bundle.resolve("config"));
        Files.createDirectories(bundle.resolve("src/app/page.test"));
        Files.createDirectories(bundle.resolve("src/route"));
        Files.createDirectories(bundle.resolve("www"));
        Files.writeString(bundle.resolve("app-api.jar"), "java-v1");
        Files.writeString(bundle.resolve("classes/Review.class"), "class-v1");
        Files.writeString(bundle.resolve("config/application.yml"), "review: initial\n");
        Files.writeString(bundle.resolve("src/app/page.test/app.json"), "{\"id\":\"page.test\"}\n");
        Files.writeString(bundle.resolve("www/index.html"), "<html>initial</html>\n");
        return new ProjectContext(
                "main",
                "com.wiz.review",
                root,
                root.resolve("src"),
                root.resolve("src/app"),
                root.resolve("src/model"),
                root.resolve("src/route"),
                root.resolve("src/assets"),
                root.resolve("config"),
                root.resolve("build"),
                bundle);
    }

    private BuildMarkerService.DependencySummary dependencies(String digest) {
        return new BuildMarkerService.DependencySummary(
                "bundle/.wiz-dependencies.json", "SHA-256", digest, 1, "bundle/bom.json");
    }

    @SuppressWarnings("unchecked")
    private String runtimeDigest(BuildMarkerService service, ProjectContext project) {
        Map<String, Object> marker = service.read(project).orElseThrow();
        return ((Map<String, Object>) marker.get("runtimeDigest")).get("value").toString();
    }
}
