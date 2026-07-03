package com.wiz.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void computesWorkspaceContextRoots() {
        PathService service = new PathService(tempDir);

        assertEquals(tempDir.toAbsolutePath().normalize(), service.root());
        assertEquals(service.root().resolve("config"), service.configRoot());
        assertEquals(service.root().resolve("public"), service.publicRoot());

        ProjectContext project = service.workspaceContext();
        assertEquals("main", project.name());
        assertEquals(service.root(), project.root());
        assertEquals(project.root().resolve("src/app"), project.appRoot());
        assertEquals(project.root().resolve("bundle/www"), project.bundleWwwRoot());
        assertEquals(project.root().resolve("bundle/src/assets"), project.bundleAssetsRoot());
    }

    @Test
    void findsJavaWorkspaceRootsOnly() throws Exception {
        Path javaRoot = tempDir.resolve("java-root");
        Files.createDirectories(javaRoot.resolve("config"));
        Files.createDirectories(javaRoot.resolve("src/app"));
        Files.writeString(javaRoot.resolve("config/application.yml"), "wiz:\n  java:\n    package-root: com.example.app\n");

        PathService service = new PathService(tempDir);
        assertEquals(javaRoot, service.findWorkspaceRoot(javaRoot.resolve("src/app")).orElseThrow());
        assertTrue(service.findWorkspaceRoot(tempDir.resolve("missing")).isEmpty());
    }

    @Test
    void safePathRejectsEscapes() {
        SafePath safePath = new SafePath(tempDir);

        assertTrue(safePath.resolve("inside/file.txt").startsWith(tempDir.toAbsolutePath().normalize()));
        assertThrows(IllegalArgumentException.class, () -> safePath.resolve("../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> safePath.resolve(Path.of("/tmp/outside.txt")));
    }

    @Test
    void safePathResolvesInternalSymlinksButRejectsSymlinkEscapes() throws Exception {
        Path inside = tempDir.resolve("inside.txt");
        Files.writeString(inside, "inside");
        Path internalLink = tempDir.resolve("internal-link.txt");
        Files.createSymbolicLink(internalLink, Path.of("inside.txt"));

        SafePath safePath = new SafePath(tempDir);

        assertEquals(inside.toRealPath(), safePath.resolveExisting("internal-link.txt"));

        Path outside = tempDir.getParent().resolve("outside.txt");
        Files.writeString(outside, "outside");
        Path escapingLink = tempDir.resolve("escaping-link.txt");
        Files.createSymbolicLink(escapingLink, outside);

        try {
            assertThrows(IllegalArgumentException.class, () -> safePath.resolveExisting("escaping-link.txt"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }
}
