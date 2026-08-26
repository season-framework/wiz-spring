package com.wiz.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.wiz.runtime.BuildMarkerService;
import com.wiz.runtime.PathService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacePackageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void changesConfigurationAndTemplateReferences() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace, "com.wiz.app");
        new ProjectService(new PathService(workspace)).createApp("com.wiz.app", null, null);

        WorkspacePackageService.PackageSelection selection = new WorkspacePackageService()
                .selectForBuild(new PathService(workspace), "com.example.initial");

        assertTrue(selection.changed());
        assertEquals("com.example.initial", selection.context().packageRoot());
        assertTrue(Files.readString(workspace.resolve("config/application.yml"))
                .contains("package-root: com.example.initial"));
        assertTrue(Files.readString(workspace.resolve("config/application.example.yml"))
                .contains("package-root: com.example.initial"));
        assertTrue(Files.readString(workspace.resolve("pom.xml"))
                .contains("<groupId>com.example.initial</groupId>"));
        String dashboardApi = Files.readString(workspace.resolve("src/app/page.dashboard/api.java"));
        assertTrue(dashboardApi.contains("com.example.initial.application.model.Struct"));
        assertFalse(dashboardApi.contains("com.wiz.app.application.model.Struct"));
    }

    @Test
    void changesPackageAfterSuccessfulBundleBuild() throws Exception {
        Path workspace = tempDir.resolve("built-workspace");
        new WorkspaceService().createWorkspace(workspace, "com.wiz.app");
        new ProjectService(new PathService(workspace)).createApp("com.wiz.app", null, null);
        Files.createDirectories(workspace.resolve("bundle"));
        Files.writeString(workspace.resolve("bundle").resolve(BuildMarkerService.MARKER_FILE), "{}\n");

        WorkspacePackageService.PackageSelection selection = new WorkspacePackageService()
                .selectForBuild(new PathService(workspace), "com.example.late");

        assertTrue(selection.changed());
        assertEquals("com.example.late", selection.context().packageRoot());
        assertEquals("com.example.late", new PathService(workspace).packageRoot());
        assertTrue(Files.readString(workspace.resolve("pom.xml"))
                .contains("<groupId>com.example.late</groupId>"));
    }

    @Test
    void addsPackageSettingWhenWorkspaceUsesTheDefaultImplicitly() throws Exception {
        Path workspace = tempDir.resolve("implicit-workspace");
        Files.createDirectories(workspace.resolve("config"));
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("config/application.yml"), "server:\n  port: 3000\nwiz:\n  api:\n    prefix: /wiz/api\n");
        Files.writeString(workspace.resolve("config/wiz.yml"), "workspace: java\n");

        WorkspacePackageService.PackageSelection selection = new WorkspacePackageService()
                .selectForBuild(new PathService(workspace), "org.example.app");

        assertTrue(selection.changed());
        assertEquals("org.example.app", new PathService(workspace).packageRoot());
        String application = Files.readString(workspace.resolve("config/application.yml"));
        assertTrue(application.contains("wiz:\n  java:\n    package-root: org.example.app\n  api:"));
    }

    @Test
    void importedParentPackageDoesNotRewriteSelectedChildPackageTwice() throws Exception {
        Path workspace = tempDir.resolve("nested-package-workspace");
        new WorkspaceService().createWorkspace(workspace, "com.example.app");
        Files.createDirectories(workspace.resolve("src/app/page.nested"));
        Files.writeString(workspace.resolve("src/app/page.nested/app.json"), "{}\n");
        Files.writeString(workspace.resolve("src/app/page.nested/api.java"), """
                import com.example.application.model.Struct;

                public final class PageNestedApi {}
                """);
        Files.writeString(workspace.resolve("config/application.example.yml"), """
                wiz:
                  java:
                    package-root: com.example
                """);

        boolean changed = new WorkspacePackageService()
                .normalizeImportedPackageReferences(new PathService(workspace), "com.example.app");

        assertTrue(changed);
        assertEquals("com.example.app", new PathService(workspace).packageRoot());
        assertTrue(Files.readString(workspace.resolve("src/app/page.nested/api.java"))
                .contains("import com.example.app.application.model.Struct;"));
        assertFalse(Files.readString(workspace.resolve("config/application.yml"))
                .contains("com.example.app.app"));
    }
}
