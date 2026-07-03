package com.wiz.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void returnsSingleWorkspaceContextAndDevModeCookie() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectService projects = new ProjectService(new PathService(workspace));
        projects.createApp(null, null);
        ProjectRegistry registry = new ProjectRegistry(new PathService(workspace));

        assertEquals("main", registry.workspace().name());
        assertEquals(false, registry.devMode(Map.of()));
        assertEquals(true, registry.devMode(Map.of(ProjectRegistry.DEFAULT_DEVMODE_COOKIE_NAME, "true")));
        assertEquals(true, registry.devMode(Map.of(ProjectRegistry.DEFAULT_DEVMODE_COOKIE_NAME, "1")));
    }

    @Test
    void returnsContextEvenBeforeSourceExists() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);

        assertEquals("main", new ProjectRegistry(new PathService(workspace)).workspace().name());
    }
}
