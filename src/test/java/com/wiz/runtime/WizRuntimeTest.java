package com.wiz.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import com.wiz.build.ProjectBuildService;
import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WizRuntimeTest {

    @TempDir
    Path tempDir;

    @Test
    void devModeRequestIncludesBuildMarkerHeaders() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        PathService pathService = new PathService(workspace);
        ProjectContext project = new ProjectService(pathService).createProject("main", null, null);
        new ProjectBuildService().build(project, true, "bundle");

        WizRuntime runtime = new WizRuntime(new ProjectRegistry(pathService));
        try (WizContext context = runtime.createContext(WizRequest.builder()
                .cookie(ProjectRegistry.DEFAULT_DEVMODE_COOKIE_NAME, "true")
                .build())) {
            WizResult result = context.response().ok(Map.of("ok", true));

            assertEquals("true", result.headers().get(WizRuntime.DEVMODE_HEADER).getFirst());
            assertTrue(result.headers().get(WizRuntime.BUILD_MARKER_HEADER).getFirst().contains("project=main"));
            assertTrue(result.headers().get(WizRuntime.BUILD_MARKER_HEADER).getFirst().contains("frontend=fallback"));
        }
    }
}