package com.wiz.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenExecutableResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void prefersExecutableWorkspaceWrapper() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        Path wrapper = workspace.resolve("mvnw");
        Files.writeString(wrapper, "#!/bin/sh\nexit 0\n");
        wrapper.toFile().setExecutable(true, false);
        Path systemBin = fakeSystemMaven();

        assertEquals(wrapper, MavenExecutableResolver.require(workspace, systemBin.toString()));
    }

    @Test
    void fallsBackToMavenOnPath() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        Path systemBin = fakeSystemMaven();

        assertEquals(systemBin.resolve("mvn").toAbsolutePath().normalize(),
                MavenExecutableResolver.require(workspace, systemBin.toString()));
    }

    @Test
    void reportsMissingAndNonExecutableMavenClearly() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);

        IllegalStateException missing = assertThrows(
                IllegalStateException.class,
                () -> MavenExecutableResolver.require(workspace, ""));
        assertTrue(missing.getMessage().contains("Maven is required"));
        assertTrue(missing.getMessage().contains("mvnw"));
        assertTrue(missing.getMessage().contains("PATH"));

        Path wrapper = workspace.resolve("mvnw");
        Files.writeString(wrapper, "#!/bin/sh\nexit 0\n");
        wrapper.toFile().setExecutable(false, false);
        IllegalStateException notExecutable = assertThrows(
                IllegalStateException.class,
                () -> MavenExecutableResolver.require(workspace, fakeSystemMaven().toString()));
        assertTrue(notExecutable.getMessage().contains("not executable"));
        assertTrue(notExecutable.getMessage().contains("chmod +x mvnw"));
    }

    private Path fakeSystemMaven() throws Exception {
        Path bin = tempDir.resolve("bin-" + System.nanoTime());
        Files.createDirectories(bin);
        Path mvn = bin.resolve(File.separatorChar == '\\' ? "mvn.cmd" : "mvn");
        Files.writeString(mvn, "#!/bin/sh\nexit 0\n");
        mvn.toFile().setExecutable(true, false);
        return bin;
    }
}
