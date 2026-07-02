package com.wiz.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;

import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void selectsCurrentProjectFromCookieOrDefault() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectService projects = new ProjectService(new PathService(workspace));
        projects.createProject("main", null, null);
        projects.createProject("sample", null, null);
        ProjectRegistry registry = new ProjectRegistry(new PathService(workspace));

        assertEquals(java.util.List.of("main", "sample"), registry.listProjects());
        assertEquals("main", registry.currentProject(Map.of()).name());
        assertEquals("sample", registry.currentProject(Map.of(ProjectRegistry.DEFAULT_PROJECT_COOKIE_NAME, "sample")).name());
        assertEquals("main", registry.currentProject(Map.of(ProjectRegistry.DEFAULT_PROJECT_COOKIE_NAME, "../bad")).name());
        assertEquals(false, registry.devMode(Map.of()));
        assertEquals(true, registry.devMode(Map.of(ProjectRegistry.DEFAULT_DEVMODE_COOKIE_NAME, "true")));
        assertEquals(true, registry.devMode(Map.of(ProjectRegistry.DEFAULT_DEVMODE_COOKIE_NAME, "1")));
    }

    @Test
    void failsClearlyWhenNoProjectsExist() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);

        assertThrows(IllegalStateException.class, () -> new ProjectRegistry(new PathService(workspace)).currentProject(Map.of()));
    }

    @Test
    void ignoresProjectCookieWhenCookieSelectionIsDisabled() throws Exception {
        Path workspace = tempDir.resolve("prod-workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectService projects = new ProjectService(new PathService(workspace));
        projects.createProject("main", null, null);
        projects.createProject("sample", null, null);
        ProjectRegistry registry = new ProjectRegistry(
                new PathService(workspace),
                ProjectRegistry.DEFAULT_PROJECT_COOKIE_NAME,
                ProjectRegistry.DEFAULT_DEVMODE_COOKIE_NAME,
                ProjectRegistry.DEFAULT_PROJECT_NAME,
                false);

        assertEquals(false, registry.cookieSelectionEnabled());
        assertEquals("main", registry.currentProject(Map.of(ProjectRegistry.DEFAULT_PROJECT_COOKIE_NAME, "sample")).name());
    }
}
