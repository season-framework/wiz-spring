package com.wiz.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createsDefaultJavaProject() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectService service = new ProjectService(new PathService(workspace));

        ProjectContext project = service.createProject("main", null, null);

        assertEquals("main", project.name());
        assertTrue(Files.exists(project.appRoot().resolve("page.access/api.java")));
        assertTrue(Files.exists(project.appRoot().resolve("page.dashboard/app.json")));
        assertTrue(Files.exists(project.appRoot().resolve("page.dashboard/api.java")));
        assertTrue(Files.exists(project.modelRoot().resolve("Struct.java")));
        assertTrue(Files.exists(project.sourceRoot().resolve("portal/post/app/list/api.java")));
        assertTrue(Files.isDirectory(project.sourceRoot().resolve("controller")));
        assertTrue(Files.exists(project.configRoot().resolve("database.yml")));
        assertFalse(Files.exists(project.root().resolve("migration-report.json")));
        assertEquals(java.util.List.of("main"), service.listProjects());
    }

    @Test
    void rewritesDefaultJavaTemplateForProjectName() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectService service = new ProjectService(new PathService(workspace));

        ProjectContext project = service.createProject("demo-app", null, null);

        String dashboardApi = Files.readString(project.appRoot().resolve("page.dashboard/api.java"));
        assertTrue(dashboardApi.contains("com.wiz.project.demo_app.model.Struct"));
        assertFalse(dashboardApi.contains("com.wiz.project.main.model.Struct"));
    }

    @Test
    void copiesProjectFromLocalPath() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path source = tempDir.resolve("source");
        Files.createDirectories(source.resolve("src/app/page.local"));
        Files.writeString(source.resolve("src/app/page.local/app.json"), "{}\n");
        Files.writeString(source.resolve("src/app/page.local/api.py"), "def api():\n    pass\n");
        new WorkspaceService().createWorkspace(workspace);

        ProjectService service = new ProjectService(new PathService(workspace));
        ProjectContext project = service.createProject("copy", null, source);

        assertTrue(Files.exists(project.appRoot().resolve("page.local/app.json")));
        assertTrue(Files.exists(project.root().resolve("migration-report.json")));
        assertTrue(Files.readString(project.root().resolve("migration-report.md")).contains("api.py"));
        assertTrue(Files.exists(project.sourceRoot().resolve("controller")));
        assertTrue(Files.exists(project.modelRoot()));
        assertTrue(Files.exists(project.routeRoot()));
    }

    @Test
    void importsZipProjectSourceAndCanGenerateJavaStubs() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path archive = tempDir.resolve("source.wizproject");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            zipEntry(output, "src/app/page.local/app.json", "{\"id\":\"page.local\"}\n");
            zipEntry(output, "src/app/page.local/api.py", "def status():\n    pass\n");
        }
        new WorkspaceService().createWorkspace(workspace);

        ProjectService service = new ProjectService(new PathService(workspace));
        ProjectContext project = service.createProject("zipcopy", null, archive, true);

        assertTrue(Files.exists(project.appRoot().resolve("page.local/app.json")));
        assertTrue(Files.exists(project.appRoot().resolve("page.local/api.java.stub")));
        assertTrue(Files.readString(project.root().resolve("migration-report.json")).contains("generatedStubFiles"));
    }

    @Test
    void exportsProjectArchiveWithoutGeneratedArtifactsAndCanImportIt() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectService service = new ProjectService(new PathService(workspace));
        ProjectContext project = service.createProject("main", null, null);
        Files.createDirectories(project.buildRoot().resolve("tmp"));
        Files.writeString(project.buildRoot().resolve("tmp/generated.txt"), "generated\n");
        Files.createDirectories(project.bundleRoot());
        Files.writeString(project.bundleRoot().resolve("project-api.jar"), "generated\n");
        Files.createDirectories(project.root().resolve(".git"));
        Files.writeString(project.root().resolve(".git/config"), "ignored\n");
        Files.writeString(project.root().resolve("migration-report.json"), "{}\n");

        Path archive = service.exportProject("main", tempDir.resolve("main-export"));

        assertEquals(tempDir.resolve("main-export.wizproject").toAbsolutePath().normalize(), archive);
        List<String> entries = zipEntries(archive);
        assertTrue(entries.contains("src/app/page.access/api.java"));
        assertTrue(entries.contains("config/database.yml"));
        assertFalse(entries.stream().anyMatch(entry -> entry.startsWith("build/")));
        assertFalse(entries.stream().anyMatch(entry -> entry.startsWith("bundle/")));
        assertFalse(entries.stream().anyMatch(entry -> entry.startsWith(".git/")));
        assertFalse(entries.contains("migration-report.json"));

        ProjectContext imported = service.createProject("imported", null, archive);
        assertTrue(Files.exists(imported.appRoot().resolve("page.access/api.java")));
        assertFalse(Files.exists(imported.buildRoot().resolve("tmp/generated.txt")));
    }

    @Test
    void rejectsDuplicateAndConflictingSources() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectService service = new ProjectService(new PathService(workspace));

        service.createProject("main", null, null);

        assertThrows(IllegalArgumentException.class, () -> service.createProject("main", null, null));
        assertThrows(IllegalArgumentException.class, () -> service.createProject("other", "https://example.test/repo.git", tempDir));
    }

    private void zipEntry(ZipOutputStream output, String name, String content) throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private List<String> zipEntries(Path archive) throws Exception {
        ArrayList<String> entries = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            zip.entries().asIterator().forEachRemaining(entry -> entries.add(entry.getName()));
        }
        return entries;
    }
}
