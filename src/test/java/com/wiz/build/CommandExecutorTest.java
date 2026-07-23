package com.wiz.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsDisallowedCommandAndEscapedCwd() throws Exception {
        CommandExecutor executor = new CommandExecutor();
        Path root = tempDir.resolve("root");
        Files.createDirectories(root);

        assertThrows(IllegalArgumentException.class, () -> executor.run("test", root, root, List.of("sh", "-c", "echo no"), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> executor.run("test", root, tempDir, List.of("node", "--version"), Duration.ofSeconds(1)));
    }

    @Test
    void capturesMaskedAndCappedOutput() throws Exception {
        CommandExecutor executor = new CommandExecutor();
        Path root = tempDir.resolve("root");
        Files.createDirectories(root);

        CommandResult result = executor.run(
                "test",
                root,
                root,
                List.of("node", "-e", "console.log('token=abc123 ' + 'x'.repeat(200))"),
                Duration.ofSeconds(2),
                32);

        assertTrue(result.success());
        assertTrue(result.cappedOutput());
        assertTrue(result.output().contains("token=***"));
        assertFalse(result.output().contains("abc123"));
        assertTrue(Files.notExists(root.resolve(".wiz")));
    }

    @Test
    void timesOutLongRunningCommand() throws Exception {
        CommandExecutor executor = new CommandExecutor();
        Path root = tempDir.resolve("root");
        Files.createDirectories(root);

        CommandResult result = executor.run(
                "test",
                root,
                root,
                List.of("node", "-e", "setTimeout(() => {}, 2000)"),
                Duration.ofMillis(100));

        assertFalse(result.success());
        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
    }

    @Test
    void allowsOnlyProjectLocalNg() throws Exception {
        CommandExecutor executor = new CommandExecutor();
        Path root = tempDir.resolve("root");
        Path localBin = root.resolve("node_modules/.bin");
        Files.createDirectories(localBin);
        Path ng = localBin.resolve("ng");
        Files.writeString(ng, "#!/usr/bin/env node\nconsole.log('ng ok')\n");
        ng.toFile().setExecutable(true);

        CommandResult result = executor.run("test", root, root, List.of("node_modules/.bin/ng"), Duration.ofSeconds(2));

        assertTrue(result.success());
        assertTrue(result.output().contains("ng ok"));
    }

    @Test
    void resolvesMavenToWorkspaceWrapper() throws Exception {
        CommandExecutor executor = new CommandExecutor();
        Path root = tempDir.resolve("wrapper-root");
        Files.createDirectories(root);
        Path wrapper = root.resolve("mvnw");
        Files.writeString(wrapper, "#!/bin/sh\nprintf 'workspace-wrapper %s\\n' \"$*\"\n");
        wrapper.toFile().setExecutable(true, false);

        CommandResult result = executor.run(
                "test",
                root,
                root,
                List.of("mvn", "--version"),
                Duration.ofSeconds(2));

        assertTrue(result.success());
        assertTrue(result.output().contains("workspace-wrapper --version"));
    }
}
