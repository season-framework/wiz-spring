package com.wiz.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createsDefaultJavaApp() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectService service = new ProjectService(new PathService(workspace));

        ProjectContext project = service.createApp(null, null);

        assertEquals("main", project.name());
        assertTrue(Files.exists(project.appRoot().resolve("page.access/api.java")));
        assertTrue(Files.exists(project.appRoot().resolve("page.dashboard/app.json")));
        assertTrue(Files.exists(project.appRoot().resolve("page.dashboard/api.java")));
        assertTrue(Files.exists(project.modelRoot().resolve("Struct.java")));
        assertTrue(Files.exists(project.sourceRoot().resolve("portal/post/app/list/api.java")));
        assertTrue(Files.isDirectory(project.sourceRoot().resolve("controller")));
        assertFalse(Files.exists(project.configRoot().resolve("database.yml")));
        assertFalse(Files.exists(project.configRoot().resolve("season.yml")));
        assertTrue(Files.exists(project.configRoot().resolve("application.yml")));
        assertTrue(Files.exists(project.configRoot().resolve("application-dev.yml")));
        assertTrue(Files.exists(project.configRoot().resolve("application-prod.yml")));
        assertTrue(Files.exists(project.configRoot().resolve("application.example.yml")));
        assertTrue(Files.exists(project.configRoot().resolve("application-dev.example.yml")));
        assertTrue(Files.exists(project.configRoot().resolve("application-prod.example.yml")));
        String application = Files.readString(project.configRoot().resolve("application.yml"));
        assertTrue(application.contains("WIZ Spring runtime settings."));
        assertTrue(application.contains("package-root: com.wiz.app"));
        assertTrue(application.contains("tracking-modes:"));
        assertTrue(application.contains("http-only: true"));
        assertTrue(application.contains("same-site: lax"));
        assertFalse(application.contains("secret:"));
        assertFalse(application.contains("prefix: /wiz/api"));
        assertFalse(application.contains("max-request-body-bytes: 0"));
        assertTrue(Files.readString(project.configRoot().resolve("application-dev.yml"))
                .contains("secure: false"));
        assertTrue(Files.readString(project.configRoot().resolve("application-prod.yml"))
                .contains("secure: true"));
        String applicationExample = Files.readString(project.configRoot().resolve("application.example.yml"));
        assertTrue(applicationExample.contains("package-root: com.wiz.app"));
        assertFalse(applicationExample.contains("secret:"));
        assertFalse(applicationExample.contains("Git에서 제외"));
        String gitignore = Files.readString(project.root().resolve(".gitignore"));
        assertTrue(gitignore.contains("/config/application.yml"));
        assertTrue(gitignore.contains("/config/application-*.yml"));
        assertTrue(gitignore.contains("!/config/application-*.example.yml"));
        assertFalse(gitignore.lines().anyMatch(line -> line.trim().equals("package-lock.json")));
        assertTrue(Files.isRegularFile(project.sourceRoot().resolve("angular/package-lock.json")));
        assertEquals("| 날짜 | ID | 작업 내용 | 상세 |\n|------|-----|----------|------|\n",
                Files.readString(project.root().resolve("devlog.md")));
        try (var devlogs = Files.list(project.root().resolve("devlog"))) {
            assertEquals(0, devlogs.count());
        }
    }

    @Test
    void packagesDefaultJavaAppTemplateAsDirectoryResource() throws Exception {
        try (var input = ProjectService.class.getResourceAsStream("/wiz/templates/default-project-java.files")) {
            assertTrue(input != null);
        }
        try (var input = ProjectService.class.getResourceAsStream("/wiz/templates/default-project-java/src/app/page.access/api.java")) {
            assertTrue(input != null);
        }
    }

    @Test
    void rewritesDefaultJavaTemplateForPackageRoot() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace, "com.example.demo");
        ProjectService service = new ProjectService(new PathService(workspace));

        ProjectContext project = service.createApp("com.example.demo", null, null);

        String dashboardApi = Files.readString(project.appRoot().resolve("page.dashboard/api.java"));
        assertTrue(dashboardApi.contains("com.example.demo.application.model.Struct"));
        assertFalse(dashboardApi.contains("com.wiz.app.application.model.Struct"));
        assertTrue(Files.readString(project.configRoot().resolve("application.example.yml"))
                .contains("package-root: com.example.demo"));
    }

    @Test
    void copiesAppFromLocalPath() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path source = tempDir.resolve("source");
        Files.createDirectories(source.resolve("src/app/page.local"));
        Files.writeString(source.resolve("src/app/page.local/app.json"), "{}\n");
        Files.writeString(source.resolve("src/app/page.local/readme.txt"), "local source\n");
        Files.createDirectories(source.resolve("devlog/2026-07-15"));
        Files.writeString(source.resolve("devlog.md"), "historical devlog\n");
        Files.writeString(source.resolve("devlog/2026-07-15/001-history.md"), "historical detail\n");
        Files.createDirectories(source.resolve("src/.wizard"));
        Files.writeString(source.resolve("src/.wizard/kept.txt"), "not a framework directory\n");
        new WorkspaceService().createWorkspace(workspace);

        ProjectService service = new ProjectService(new PathService(workspace));
        ProjectContext project = service.createApp(null, source);

        assertTrue(Files.exists(project.appRoot().resolve("page.local/app.json")));
        assertTrue(Files.exists(project.appRoot().resolve("page.local/readme.txt")));
        assertTrue(Files.exists(project.sourceRoot().resolve("controller")));
        assertTrue(Files.exists(project.modelRoot()));
        assertTrue(Files.exists(project.routeRoot()));
        assertTrue(Files.readString(project.root().resolve(".gitignore"))
                .contains("/config/application-*.yml"));
        assertEquals("historical devlog\n", Files.readString(project.root().resolve("devlog.md")));
        assertEquals("historical detail\n",
                Files.readString(project.root().resolve("devlog/2026-07-15/001-history.md")));
        assertTrue(Files.isRegularFile(project.sourceRoot().resolve(".wizard/kept.txt")));
    }

    @Test
    void clonesOutsideWorkspaceAndCleansTemporaryDirectoryAfterSuccess() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        AtomicReference<Path> cloneTarget = new AtomicReference<>();
        ProjectService service = new ProjectService(new PathService(workspace), (uri, target) -> {
            cloneTarget.set(target);
            assertFalse(target.startsWith(workspace));
            assertTrue(target.getFileName().toString().startsWith("wiz-clone-"));
            assertFalse(target.getFileName().toString().startsWith("."));
            Files.createDirectories(target.resolve("src/app/page.remote"));
            Files.writeString(target.resolve("src/app/page.remote/app.json"), "{}\n");
            Files.createDirectories(target.resolve(".git/objects"));
            Files.writeString(target.resolve(".git/objects/ignored.txt"), "git metadata\n");
        });

        ProjectContext project = service.createApp("https://example.test/project.git", null);

        assertTrue(Files.isRegularFile(project.appRoot().resolve("page.remote/app.json")));
        assertFalse(Files.exists(project.root().resolve(".git")));
        assertNotNull(cloneTarget.get());
        assertFalse(Files.exists(cloneTarget.get()));
    }

    @Test
    void cleansTemporaryCloneDirectoryAfterFailure() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        AtomicReference<Path> cloneTarget = new AtomicReference<>();
        ProjectService service = new ProjectService(new PathService(workspace), (uri, target) -> {
            cloneTarget.set(target);
            Files.writeString(target.resolve("partial-clone.txt"), "partial\n");
            throw new IOException("simulated clone failure");
        });

        assertThrows(IOException.class,
                () -> service.createApp("https://example.test/project.git", null));

        assertNotNull(cloneTarget.get());
        assertFalse(Files.exists(cloneTarget.get()));
        try (var siblings = Files.list(tempDir)) {
            assertFalse(siblings.anyMatch(path -> path.getFileName().toString().startsWith("wiz-clone-")));
        }
    }

    @Test
    void rejectsDuplicateAndConflictingSources() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectService service = new ProjectService(new PathService(workspace));

        service.createApp(null, null);

        assertThrows(IllegalArgumentException.class, () -> service.createApp(null, null));
        assertThrows(IllegalArgumentException.class, () -> service.createApp("https://example.test/repo.git", tempDir));
    }

}
