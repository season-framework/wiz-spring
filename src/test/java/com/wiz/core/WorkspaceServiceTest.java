package com.wiz.core;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createsJavaWorkspaceWithoutIdeOrPluginRoots() throws Exception {
        Path root = tempDir.resolve("workspace");
        WorkspaceService.CreatedWorkspace workspace = new WorkspaceService().createWorkspace(root);

        assertTrue(Files.isDirectory(workspace.root().resolve("config")));
        assertTrue(!Files.exists(workspace.root().resolve("public")));
        assertTrue(Files.isDirectory(workspace.root().resolve("project")));
        assertTrue(Files.isRegularFile(workspace.root().resolve("config/wiz.yml")));
        assertTrue(!Files.exists(workspace.root().resolve("ide")));
        assertTrue(!Files.exists(workspace.root().resolve("plugin")));
    }

    @Test
    void rejectsExistingWorkspacePath() throws Exception {
        Files.createDirectories(tempDir.resolve("existing"));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceService().createWorkspace(tempDir.resolve("existing")));
    }
}
