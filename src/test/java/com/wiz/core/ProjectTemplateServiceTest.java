package com.wiz.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class ProjectTemplateServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsStandaloneProjectWithoutHiddenWizMetadata() throws Exception {
        Path target = tempDir.resolve("demo-project");

        ProjectTemplateService.GeneratedProject project = new ProjectTemplateService().create(
                target, "com.example.demo", FrontendTemplate.ANGULAR_WIZ, null, null);

        assertEquals(target.toAbsolutePath().normalize(), project.root());
        assertEquals("demo-project", project.artifactId());
        assertEquals(FrontendTemplate.ANGULAR_WIZ, project.template());
        assertFalse(project.imported());
        assertTrue(Files.isRegularFile(target.resolve("scripts/wizbuild.mjs")));
        assertTrue(Files.readString(target.resolve("package.json"))
                .contains("\"frontend\": \"angular-wiz\""));
        assertTrue(Files.readString(target.resolve("pom.xml"))
                .contains("spring-boot-starter-data-jpa"));
        assertTrue(Files.readString(target.resolve("src/main/resources/application.yml"))
                .contains("jdbc:h2:file:./data/sample"));
        assertFalse(Files.exists(target.resolve(".wiz")));
        assertFalse(Files.exists(target.resolve("config/wiz.yml")));
    }

    @Test
    void importsOnlyStandardProjectsAndAppliesManagedScripts() throws Exception {
        Path source = tempDir.resolve("standard-source");
        Files.createDirectories(source);
        String sourcePackage = """
                {
                  "name": "existing-app",
                  "scripts": {"build": "webpack", "test": "node --test"},
                  "dependencies": {"existing-library": "1.2.3"}
                }
                """;
        Files.writeString(source.resolve("package.json"), sourcePackage);
        Files.writeString(source.resolve("package-lock.json"), "{\"lockfileVersion\":3}\n");
        Files.writeString(source.resolve("keep.txt"), "keep me\n");
        writeCompatibleFrontend(source, FrontendTemplate.HTML);
        Path target = tempDir.resolve("standard-target");

        ProjectTemplateService.GeneratedProject generated = new ProjectTemplateService().create(
                target, "com.example.demo", FrontendTemplate.HTML, null, source);

        Map<String, Object> manifest = packageJson(target);
        Map<String, Object> scripts = object(manifest.get("scripts"));
        assertTrue(generated.imported());
        assertEquals("existing-app", manifest.get("name"));
        assertEquals("node scripts/build.mjs", scripts.get("build"));
        assertEquals("node --test", scripts.get("test"));
        assertFalse(scripts.containsKey("original:build"));
        assertEquals("html", object(manifest.get("wiz")).get("frontend"));
        assertEquals("keep me\n", Files.readString(target.resolve("keep.txt")));
        assertEquals("{\"lockfileVersion\":3}\n", Files.readString(target.resolve("package-lock.json")));
        assertEquals(sourcePackage, Files.readString(source.resolve("package.json")));
        assertFalse(Files.readString(target.resolve("pom.xml"))
                .contains("spring-boot-starter-data-jpa"));
        assertFalse(Files.readString(target.resolve("pom.xml"))
                .contains("spring-security-crypto"));
        assertFalse(Files.readString(target.resolve("src/main/resources/application.yml"))
                .contains("jdbc:h2:file:./data/sample"));
    }

    @Test
    void standardBuildFilesAreReplacedWithoutTreatingThemAsAZeroXMigration() throws Exception {
        Path source = tempDir.resolve("replace-source");
        Files.createDirectories(source);
        Files.writeString(source.resolve("package.json"), "{}\n");
        Files.writeString(source.resolve("pom.xml"), "user pom\n");
        writeCompatibleFrontend(source, FrontendTemplate.HTML);
        Path target = tempDir.resolve("replace-target");

        new ProjectTemplateService().create(
                target, "com.example.demo", FrontendTemplate.HTML, null, source);

        assertFalse(Files.readString(target.resolve("pom.xml")).contains("user pom"));
        Path archived = target.resolve("replaced-originals/wiz-spring-import");
        assertEquals("user pom\n", Files.readString(archived.resolve("pom.xml")));
        assertTrue(Files.readString(archived.resolve("README.md"))
                .contains("replaced by the selected 1.0 template"));
        assertEquals("user pom\n", Files.readString(source.resolve("pom.xml")));
    }

    @Test
    void preservesUnrelatedCodexConfiguration() throws Exception {
        Path source = tempDir.resolve("codex-source");
        Files.createDirectories(source.resolve(".codex"));
        Files.writeString(source.resolve("package.json"), "{}\n");
        String config = "[mcp_servers.database]\ncommand = \"database-mcp\"\n";
        Files.writeString(source.resolve(".codex/config.toml"), config);
        writeCompatibleFrontend(source, FrontendTemplate.HTML);
        Path target = tempDir.resolve("codex-target");

        new ProjectTemplateService().create(
                target, "com.example.demo", FrontendTemplate.HTML, null, source);

        assertEquals(config, Files.readString(target.resolve(".codex/config.toml")));
        assertEquals(config, Files.readString(source.resolve(".codex/config.toml")));
    }

    @Test
    void rejectsWizDirectoryWithoutPublishingOrMutating() throws Exception {
        Path source = tempDir.resolve("wiz-directory-source");
        Path marker = source.resolve(".wiz/metadata");
        Files.createDirectories(marker.getParent());
        Files.writeString(source.resolve("package.json"), "{}\n");
        Files.writeString(marker, "metadata\n");
        Path target = tempDir.resolve("wiz-directory-target");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                new ProjectTemplateService().create(
                        target, "com.example.demo", FrontendTemplate.HTML, null, source));

        assertTrue(error.getMessage().contains("never creates or publishes a .wiz directory"));
        assertFalse(Files.exists(target));
        assertTrue(Files.exists(marker));
    }

    @Test
    void rejectsExternalBuilderReferences() throws Exception {
        Path npmSource = tempDir.resolve("npm-source");
        Files.createDirectories(npmSource);
        String manifest = "{\"dependencies\":{\"@season-framework/wiz-frontend\":\"1\"}}\n";
        Files.writeString(npmSource.resolve("package.json"), manifest);
        Path npmTarget = tempDir.resolve("npm-target");

        IllegalArgumentException npmError = assertThrows(IllegalArgumentException.class, () ->
                new ProjectTemplateService().create(
                        npmTarget, "com.example.demo", FrontendTemplate.HTML, null, npmSource));
        assertTrue(npmError.getMessage().contains("@season-framework/wiz-frontend"));
        assertFalse(Files.exists(npmTarget));
        assertEquals(manifest, Files.readString(npmSource.resolve("package.json")));
    }

    @Test
    void rejectsAnExistingWizManifestInsteadOfRegeneratingIt() throws Exception {
        Path source = tempDir.resolve("existing-wiz-source");
        Files.createDirectories(source);
        Files.writeString(source.resolve("package.json"),
                "{\"wiz\":{\"frontend\":\"react\"}}\n");
        Path target = tempDir.resolve("existing-wiz-target");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                new ProjectTemplateService().create(
                        target, "com.example.demo", FrontendTemplate.REACT, null, source));

        assertTrue(error.getMessage().contains("managed package.json field 'wiz'"));
        assertFalse(Files.exists(target));
    }

    @Test
    void rejectsImportsThatDoNotMatchTheSelectedOnePointZeroFrontendLayout() throws Exception {
        Map<FrontendTemplate, String> expectedMissingPath = Map.of(
                FrontendTemplate.ANGULAR_WIZ, "src/app/",
                FrontendTemplate.ANGULAR, "frontend/src/index.html",
                FrontendTemplate.REACT, "frontend/index.html",
                FrontendTemplate.HTML, "frontend/index.html",
                FrontendTemplate.JSP, "src/main/webapp/WEB-INF/jsp/");
        for (FrontendTemplate template : FrontendTemplate.values()) {
            Path source = tempDir.resolve("layout-source-" + template.id());
            Files.createDirectories(source);
            Files.writeString(source.resolve("package.json"), "{}\n");
            Path target = tempDir.resolve("layout-target-" + template.id());

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                    new ProjectTemplateService().create(
                            target, "com.example.demo", template, null, source), template.id());

            assertTrue(error.getMessage().contains("required 1.0 layout"), template.id());
            assertTrue(error.getMessage().contains(expectedMissingPath.get(template)), template.id());
            assertFalse(Files.exists(target), template.id());
            assertEquals("{}\n", Files.readString(source.resolve("package.json")), template.id());
        }
    }

    @Test
    void rejectsRootLevelAngularAndReactLayoutsInsteadOfPublishingBrokenBuildConfigs() throws Exception {
        Map<FrontendTemplate, List<Path>> rootLayouts = Map.of(
                FrontendTemplate.ANGULAR, List.of(
                        Path.of("src/index.html"), Path.of("src/main.ts"), Path.of("src/styles.css")),
                FrontendTemplate.REACT, List.of(
                        Path.of("index.html"), Path.of("src/main.jsx")));
        for (Map.Entry<FrontendTemplate, List<Path>> entry : rootLayouts.entrySet()) {
            FrontendTemplate template = entry.getKey();
            Path source = tempDir.resolve("root-layout-source-" + template.id());
            Files.createDirectories(source);
            Files.writeString(source.resolve("package.json"), "{}\n");
            for (Path relative : entry.getValue()) {
                write(source.resolve(relative), "// existing root-level frontend\n");
            }
            Path target = tempDir.resolve("root-layout-target-" + template.id());

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                    new ProjectTemplateService().create(
                            target, "com.example.demo", template, null, source));

            assertTrue(error.getMessage().contains("frontend/"), template.id());
            assertFalse(Files.exists(target), template.id());
            for (Path relative : entry.getValue()) {
                assertTrue(Files.exists(source.resolve(relative)), template.id() + ": " + relative);
            }
        }
    }

    @Test
    void rejectsNonStandardJavaSources() throws Exception {
        Path source = tempDir.resolve("java-source");
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/api.java"), "class Api {}\n");
        Path target = tempDir.resolve("java-target");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                new ProjectTemplateService().create(
                        target, "com.example.demo", FrontendTemplate.HTML, null, source));

        assertTrue(error.getMessage().contains("outside src/main/java"));
        assertFalse(Files.exists(target));
        assertTrue(Files.exists(source.resolve("src/api.java")));
    }

    @Test
    void rejectsUnsafeManifestEntries() {
        assertThrows(IllegalArgumentException.class,
                () -> ProjectTemplateService.validateManifestEntry("../escape.txt", "test.files"));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectTemplateService.validateManifestEntry("..\\escape.txt", "test.files"));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectTemplateService.validateManifestEntry("a//b.txt", "test.files"));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectTemplateService.validateManifestEntry("/absolute.txt", "test.files"));
    }

    @Test
    void rejectsUnsafeEmbeddedManifestBeforePublishing() {
        MapResources resources = new MapResources()
                .put(ProjectTemplateService.COMMON_MANIFEST, "../escape.txt\n")
                .put("/wiz/templates/project-angular-wiz.files", "package.json\n")
                .put("/wiz/templates/project-angular-wiz/package.json", """
                        {"wiz":{"frontend":"angular-wiz"},"scripts":{}}
                        """);
        ProjectTemplateService service = new ProjectTemplateService(resources, (uri, cloneTarget) -> {
            throw new AssertionError("git clone was not expected");
        });
        Path target = tempDir.resolve("unsafe");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.create(
                target, "com.example.demo", FrontendTemplate.ANGULAR_WIZ, null, null));

        assertTrue(error.getMessage().contains("Unsafe embedded template entry"));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(tempDir.resolve("escape.txt")));
    }

    @Test
    void rejectsUnsafeGitUriBeforeClone() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectTemplateService().create(
                tempDir.resolve("git-import"),
                "com.example.demo",
                FrontendTemplate.REACT,
                "--upload-pack=malicious",
                null));
    }

    @Test
    void rejectsInvalidJavaPackageRoots() {
        for (String packageRoot : new String[] {
                "", "java.demo", "com.class.demo", "com.example-demo"
        }) {
            assertThrows(IllegalArgumentException.class, () -> new ProjectTemplateService().create(
                    tempDir.resolve("invalid-" + Math.abs(packageRoot.hashCode())),
                    packageRoot,
                    FrontendTemplate.HTML,
                    null,
                    null));
        }
    }

    @Test
    void rejectsSymlinksInLocalImports() throws Exception {
        Path source = tempDir.resolve("symlink-source");
        Files.createDirectories(source);
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "outside\n");
        try {
            Files.createSymbolicLink(source.resolve("linked.txt"), outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                new ProjectTemplateService().create(
                        tempDir.resolve("symlink-target"),
                        "com.example.demo",
                        FrontendTemplate.HTML,
                        null,
                        source));

        assertTrue(error.getMessage().contains("Symbolic links are not allowed"));
        assertFalse(Files.exists(tempDir.resolve("symlink-target")));
    }

    private Map<String, Object> packageJson(Path target) throws Exception {
        return objectMapper.readValue(
                Files.readAllBytes(target.resolve("package.json")),
                new TypeReference<Map<String, Object>>() {
                });
    }

    private void writeCompatibleFrontend(Path root, FrontendTemplate template) throws Exception {
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

    private void write(Path file, String contents) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }

    private Map<String, Object> object(Object value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    private static final class MapResources implements ProjectTemplateService.TemplateResources {
        private final Map<String, byte[]> values = new HashMap<>();

        private MapResources put(String path, String contents) {
            values.put(path, contents.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        @Override
        public InputStream open(String resourcePath) {
            byte[] value = values.get(resourcePath);
            return value == null ? null : new ByteArrayInputStream(value);
        }
    }
}
