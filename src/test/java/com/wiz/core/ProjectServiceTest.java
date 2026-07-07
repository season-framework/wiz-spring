package com.wiz.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

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
        assertTrue(Files.exists(project.configRoot().resolve("database.yml")));
        assertTrue(Files.exists(project.configRoot().resolve("application.yml")));
        assertTrue(Files.exists(project.configRoot().resolve("application-dev.yml")));
        assertTrue(Files.exists(project.configRoot().resolve("application-prod.yml")));
        String application = Files.readString(project.configRoot().resolve("application.yml"));
        assertTrue(application.contains("WIZ Spring runtime settings."));
        assertTrue(application.contains("package-root: com.wiz.app"));
        assertTrue(application.contains("prefix: /wiz/api"));
        assertTrue(application.contains("max-request-body-bytes: 0"));
        assertTrue(Files.readString(project.configRoot().resolve("application-dev.yml")).contains("warmup-enabled: true"));
        assertTrue(Files.readString(project.configRoot().resolve("application-prod.yml")).contains("warmup-enabled: true"));
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
        new WorkspaceService().createWorkspace(workspace);
        ProjectService service = new ProjectService(new PathService(workspace));

        ProjectContext project = service.createApp("com.example.demo", null, null);

        String dashboardApi = Files.readString(project.appRoot().resolve("page.dashboard/api.java"));
        assertTrue(dashboardApi.contains("com.example.demo.application.model.Struct"));
        assertFalse(dashboardApi.contains("com.wiz.app.application.model.Struct"));
    }

    @Test
    void copiesAppFromLocalPath() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path source = tempDir.resolve("source");
        Files.createDirectories(source.resolve("src/app/page.local"));
        Files.writeString(source.resolve("src/app/page.local/app.json"), "{}\n");
        Files.writeString(source.resolve("src/app/page.local/readme.txt"), "local source\n");
        new WorkspaceService().createWorkspace(workspace);

        ProjectService service = new ProjectService(new PathService(workspace));
        ProjectContext project = service.createApp(null, source);

        assertTrue(Files.exists(project.appRoot().resolve("page.local/app.json")));
        assertTrue(Files.exists(project.appRoot().resolve("page.local/readme.txt")));
        assertTrue(Files.exists(project.sourceRoot().resolve("controller")));
        assertTrue(Files.exists(project.modelRoot()));
        assertTrue(Files.exists(project.routeRoot()));
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
