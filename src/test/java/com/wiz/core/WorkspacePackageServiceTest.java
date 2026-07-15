package com.wiz.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void changesConfigurationAndTemplateReferencesBeforeInitialBuild() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace, "com.wiz.app");
        new ProjectService(new PathService(workspace)).createApp("com.wiz.app", null, null);

        WorkspacePackageService.PackageSelection selection = new WorkspacePackageService()
                .selectForBuild(new PathService(workspace), "com.example.initial");

        assertTrue(selection.changed());
        assertEquals("com.example.initial", selection.context().packageRoot());
        assertTrue(Files.readString(workspace.resolve("config/application.yml"))
                .contains("package-root: com.example.initial"));
        assertTrue(Files.readString(workspace.resolve("pom.xml"))
                .contains("<groupId>com.example.initial</groupId>"));
        String dashboardApi = Files.readString(workspace.resolve("src/app/page.dashboard/api.java"));
        assertTrue(dashboardApi.contains("com.example.initial.application.model.Struct"));
        assertFalse(dashboardApi.contains("com.wiz.app.application.model.Struct"));
    }

    @Test
    void rejectsChangingPackageAfterSuccessfulBundleBuild() throws Exception {
        Path workspace = tempDir.resolve("built-workspace");
        new WorkspaceService().createWorkspace(workspace, "com.wiz.app");
        new ProjectService(new PathService(workspace)).createApp("com.wiz.app", null, null);
        Files.createDirectories(workspace.resolve("bundle"));
        Files.writeString(workspace.resolve("bundle").resolve(BuildMarkerService.MARKER_FILE), "{}\n");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new WorkspacePackageService().selectForBuild(new PathService(workspace), "com.example.late"));

        assertTrue(exception.getMessage().contains("before the first successful bundle build"));
        assertEquals("com.wiz.app", new PathService(workspace).packageRoot());
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
}
