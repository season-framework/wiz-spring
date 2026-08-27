package com.wiz.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SampleTemplateBoundaryTest {

    private static final String PACKAGE_ROOT = "com.example.boundary";
    private static final String PACKAGE_PATH = "com/example/boundary";
    private static final String PACKAGE_PLACEHOLDER = "__WIZ_PACKAGE_PATH__";
    private static final String COMMON_INFRA_MANIFEST = "/wiz/templates/project-common.files";
    private static final String COMMON_SAMPLE_MANIFEST = "/wiz/templates/project-common-sample.files";
    private static final String COMMON_RESOURCE_ROOT = "/wiz/templates/project-common/";
    private static final String AUTH_CONTROLLER_ENTRY =
            "src/main/java/__WIZ_PACKAGE_PATH__/api/AuthController.java";
    private static final Map<FrontendTemplate, String> FRONTEND_SAMPLE_ENTRY = Map.of(
            FrontendTemplate.ANGULAR_WIZ, "src/app/page.dashboard/app.json",
            FrontendTemplate.ANGULAR, "frontend/src/app/pages/dashboard-page.component.ts",
            FrontendTemplate.REACT, "frontend/src/pages/DashboardPage.jsx",
            FrontendTemplate.HTML, "frontend/views/dashboard.js",
            FrontendTemplate.JSP, "src/main/webapp/WEB-INF/jsp/dashboard.jsp");

    @TempDir
    Path tempDir;

    @Test
    void classpathManifestsReferenceSafeUniqueExistingFilesAndKeepSamplesSeparate() throws Exception {
        List<ManifestLayer> layers = new ArrayList<>();
        layers.add(new ManifestLayer(COMMON_INFRA_MANIFEST, COMMON_RESOURCE_ROOT));
        layers.add(new ManifestLayer(COMMON_SAMPLE_MANIFEST, COMMON_RESOURCE_ROOT));
        for (FrontendTemplate template : FrontendTemplate.values()) {
            layers.add(infrastructureLayer(template));
            layers.add(sampleLayer(template));
        }

        for (ManifestLayer layer : layers) {
            List<String> entries = manifestEntries(layer.manifest());
            assertFalse(entries.isEmpty(), () -> "Manifest must not be empty: " + layer.manifest());
            Set<String> unique = new HashSet<>();
            for (String entry : entries) {
                ProjectTemplateService.validateManifestEntry(entry, layer.manifest());
                assertTrue(unique.add(entry), () -> "Duplicate manifest entry: " + layer.manifest() + " -> " + entry);
                try (InputStream resource = SampleTemplateBoundaryTest.class
                        .getResourceAsStream(layer.resourceRoot() + entry)) {
                    assertNotNull(resource,
                            () -> "Manifest references a missing classpath resource: "
                                    + layer.resourceRoot() + entry);
                }
            }
        }

        assertDisjoint(COMMON_INFRA_MANIFEST, COMMON_SAMPLE_MANIFEST);
        assertTrue(manifestEntries(COMMON_SAMPLE_MANIFEST).contains(AUTH_CONTROLLER_ENTRY));
        for (FrontendTemplate template : FrontendTemplate.values()) {
            ManifestLayer infrastructure = infrastructureLayer(template);
            ManifestLayer sample = sampleLayer(template);
            assertDisjoint(infrastructure.manifest(), sample.manifest());
            assertTrue(manifestEntries(sample.manifest()).contains(FRONTEND_SAMPLE_ENTRY.get(template)),
                    () -> "Core frontend sample must be fresh-only for " + template.id());
        }
    }

    @Test
    void freshProjectsReceiveEveryDeclaredSampleAcrossAllFrontendTemplates() throws Exception {
        for (FrontendTemplate template : FrontendTemplate.values()) {
            Path target = tempDir.resolve("fresh-" + template.id());

            ProjectTemplateService.GeneratedProject generated = new ProjectTemplateService().create(
                    target, PACKAGE_ROOT, template, null, null);

            assertFalse(generated.imported(), template.id());
            assertManifestFilesPresent(target, COMMON_SAMPLE_MANIFEST);
            assertManifestFilesPresent(target, sampleLayer(template).manifest());
            assertTrue(Files.isRegularFile(target.resolve(generatedPath(AUTH_CONTROLLER_ENTRY))), template.id());
            assertTrue(Files.isRegularFile(target.resolve(generatedPath(FRONTEND_SAMPLE_ENTRY.get(template)))),
                    template.id());
        }
    }

    @Test
    void localImportsReceiveInfrastructureButNeverAnyDeclaredSample() throws Exception {
        for (FrontendTemplate template : FrontendTemplate.values()) {
            Path source = tempDir.resolve("source-" + template.id());
            Files.createDirectories(source);
            Files.writeString(source.resolve("keep.txt"), "preserved\n", StandardCharsets.UTF_8);
            writeCompatibleFrontend(source, template);
            Set<Path> importedFiles = regularFilesBelow(source);
            Path target = tempDir.resolve("imported-" + template.id());

            ProjectTemplateService.GeneratedProject generated = new ProjectTemplateService().create(
                    target, PACKAGE_ROOT, template, null, source);

            assertTrue(generated.imported(), template.id());
            assertManifestFilesAbsent(target, COMMON_SAMPLE_MANIFEST, importedFiles);
            assertManifestFilesAbsent(target, sampleLayer(template).manifest(), importedFiles);
            assertManifestFilesPresent(target, COMMON_INFRA_MANIFEST);
            assertManifestFilesPresent(target, infrastructureLayer(template).manifest());
            assertFalse(Files.exists(target.resolve(generatedPath(AUTH_CONTROLLER_ENTRY))), template.id());
            assertFalse(Files.exists(target.resolve(generatedPath(FRONTEND_SAMPLE_ENTRY.get(template)))),
                    template.id());
            assertEquals("preserved\n", Files.readString(target.resolve("keep.txt"), StandardCharsets.UTF_8));
        }
    }

    private void assertManifestFilesPresent(Path target, String manifest) throws IOException {
        for (String entry : manifestEntries(manifest)) {
            Path generated = target.resolve(generatedPath(entry));
            assertTrue(Files.isRegularFile(generated),
                    () -> "Expected generated file from " + manifest + ": " + generated);
        }
    }

    private void writeCompatibleFrontend(Path root, FrontendTemplate template) throws IOException {
        switch (template) {
            case ANGULAR_WIZ -> Files.createDirectories(root.resolve("src/app"));
            case ANGULAR -> {
                write(root.resolve("frontend/src/index.html"), "<app-root></app-root>\n");
                write(root.resolve("frontend/src/main.ts"), "export {};\n");
                write(root.resolve("frontend/src/styles.css"), "\n");
            }
            case REACT -> {
                write(root.resolve("frontend/index.html"), "<div id=\"root\"></div>\n");
                Files.createDirectories(root.resolve("frontend/src"));
            }
            case HTML -> write(root.resolve("frontend/index.html"), "<!doctype html><title>import</title>\n");
            case JSP -> Files.createDirectories(root.resolve("src/main/webapp/WEB-INF/jsp"));
        }
    }

    private void write(Path file, String contents) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents, StandardCharsets.UTF_8);
    }

    private void assertManifestFilesAbsent(Path target, String manifest, Set<Path> importedFiles) throws IOException {
        for (String entry : manifestEntries(manifest)) {
            Path relative = generatedPath(entry);
            if (importedFiles.contains(relative)) {
                continue;
            }
            Path generated = target.resolve(relative);
            assertFalse(Files.exists(generated),
                    () -> "Imported project received fresh-only sample from " + manifest + ": " + generated);
        }
    }

    private Set<Path> regularFilesBelow(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private void assertDisjoint(String infrastructureManifest, String sampleManifest) throws IOException {
        Set<String> infrastructure = new HashSet<>(manifestEntries(infrastructureManifest));
        Set<String> sample = new HashSet<>(manifestEntries(sampleManifest));
        infrastructure.retainAll(sample);
        assertTrue(infrastructure.isEmpty(),
                () -> "Infrastructure and sample manifests overlap: "
                        + infrastructureManifest + " / " + sampleManifest + " -> " + infrastructure);
    }

    private List<String> manifestEntries(String manifest) throws IOException {
        try (InputStream resource = SampleTemplateBoundaryTest.class.getResourceAsStream(manifest)) {
            assertNotNull(resource, () -> "Missing classpath manifest: " + manifest);
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::strip)
                    .map(line -> line.startsWith("\uFEFF") ? line.substring(1).strip() : line)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }
    }

    private Path generatedPath(String manifestEntry) {
        return Path.of(manifestEntry.replace(PACKAGE_PLACEHOLDER, PACKAGE_PATH));
    }

    private ManifestLayer infrastructureLayer(FrontendTemplate template) {
        return new ManifestLayer(
                "/wiz/templates/project-" + template.id() + ".files",
                "/wiz/templates/project-" + template.id() + "/");
    }

    private ManifestLayer sampleLayer(FrontendTemplate template) {
        return new ManifestLayer(
                "/wiz/templates/project-" + template.id() + "-sample.files",
                "/wiz/templates/project-" + template.id() + "/");
    }

    private record ManifestLayer(String manifest, String resourceRoot) {
    }
}
