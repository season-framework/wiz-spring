package com.wiz.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceRuntimePathsTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsProcessAndMcpStateOutsideTheWorkspace() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path otherWorkspace = tempDir.resolve("other-workspace");
        Files.createDirectories(workspace);
        Files.createDirectories(otherWorkspace);

        Path buildLock = WorkspaceRuntimePaths.buildLock(workspace);
        Path snapshots = WorkspaceRuntimePaths.runtimeSnapshots(workspace);
        Path compilerClasspath = WorkspaceRuntimePaths.compilerClasspathCache(workspace);
        Path mcpState = WorkspaceRuntimePaths.mcpState(workspace);

        assertFalse(buildLock.startsWith(workspace));
        assertFalse(snapshots.startsWith(workspace));
        assertFalse(compilerClasspath.startsWith(workspace));
        assertFalse(mcpState.startsWith(workspace));
        assertFalse(buildLock.toString().contains("/.wiz/"));
        assertFalse(snapshots.toString().contains("/.wiz/"));
        assertFalse(compilerClasspath.toString().contains("/.wiz/"));
        assertFalse(mcpState.toString().contains("/.wiz/"));
        assertEquals(buildLock, WorkspaceRuntimePaths.buildLock(workspace.resolve(".")));
        assertNotEquals(buildLock, WorkspaceRuntimePaths.buildLock(otherWorkspace));
        assertNotEquals(buildLock.getParent(), snapshots.getParent(), "locks and large snapshots use separate stores");
        assertEquals(snapshots.getParent().getParent(), compilerClasspath.getParent().getParent(),
                "large caches share the workspace cache store");
    }

    @Test
    void preparesOwnerOnlyExternalStateDirectoriesAndFiles() throws Exception {
        Path workspace = tempDir.resolve("private-workspace");
        Files.createDirectories(workspace);
        Path lock = WorkspaceRuntimePaths.prepareBuildLock(workspace);
        Path snapshots = WorkspaceRuntimePaths.prepareRuntimeSnapshots(workspace);
        Path compilerClasspath = WorkspaceRuntimePaths.prepareCompilerClasspathCache(workspace);
        Path state = WorkspaceRuntimePaths.prepareMcpState(workspace);
        try {
            Files.writeString(lock, "");
            WorkspaceRuntimePaths.secureFile(lock);

            if (Files.getFileStore(lock).supportsFileAttributeView("posix")) {
                assertEquals(Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(lock));
                assertEquals(Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(lock.getParent()));
                assertEquals(Files.getPosixFilePermissions(lock.getParent()), Files.getPosixFilePermissions(snapshots));
                assertEquals(Files.getPosixFilePermissions(lock.getParent()), Files.getPosixFilePermissions(compilerClasspath));
                assertEquals(Files.getPosixFilePermissions(lock.getParent()), Files.getPosixFilePermissions(state.getParent()));
            }
            assertFalse(lock.startsWith(workspace));
            assertFalse(snapshots.startsWith(workspace));
            assertFalse(compilerClasspath.startsWith(workspace));
            assertFalse(state.startsWith(workspace));
        } finally {
            deleteTree(lock.getParent());
            deleteTree(snapshots.getParent());
            deleteTree(compilerClasspath.getParent().getParent());
            deleteTree(state.getParent());
        }
    }

    @Test
    void rejectsStatePathsInsideWorkspaceBeforeCreatingThem() throws Exception {
        Path workspace = tempDir.resolve("path-policy-workspace");
        Files.createDirectories(workspace);
        Path workspaceState = workspace.resolve("state/mcp.json");

        assertThrows(java.io.IOException.class,
                () -> WorkspaceRuntimePaths.requireOutsideWorkspace(workspace, workspaceState));
        assertFalse(Files.exists(workspace.resolve("state")));
    }

    private void deleteTree(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
        assertTrue(Files.notExists(path));
    }
}
