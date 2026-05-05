package com.wiz.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import com.wiz.build.ProjectBuildService;
import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.ProjectRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticFileServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void findsAssetsAndSpaFallbackFromCurrentBundle() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        new ProjectBuildService().build(project, true, "bundle");
        StaticFileService service = new StaticFileService(new ProjectRegistry(new PathService(workspace)));

        StaticFileService.StaticFile asset = service.findAsset("lang/en.json", Map.of()).orElseThrow();
        StaticFileService.StaticFile index = service.findSpaFile("/unknown/deep/path", Map.of()).orElseThrow();

        assertEquals(project.bundleAssetsRoot().resolve("lang/en.json"), asset.path());
        assertTrue(asset.mediaType().startsWith("application/json"));
        assertEquals(project.bundleWwwRoot().resolve("index.html"), index.path());
        assertTrue(index.mediaType().startsWith("text/html"));
        assertTrue(service.findAsset("../config/wiz.yml", Map.of()).isEmpty());
    }
}