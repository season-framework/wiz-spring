package com.wiz.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

class DevelopmentToolchainTest {

    @Test
    void acceptsTheSupportedNodeLinesAndNpmTen() {
        for (String node : List.of("v22.22.3", "v24.15.0", "v26.0.0", "v26.8.1")) {
            DevelopmentToolchain toolchain = toolchain(21, true, node, "10.0.0");
            DevelopmentToolchain.Report report = toolchain.verify();

            assertEquals(node.substring(1), report.nodeVersion());
            assertEquals("10.0.0", report.npmVersion());
        }
    }

    @Test
    void rejectsUnsupportedNodeLinesAndBoundaryVersions() {
        for (String node : List.of(
                "v22.22.2", "v23.9.0", "v24.14.9", "v25.0.0", "v27.0.0", "v28.0.0", "v24.15.0-rc.1")) {
            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> toolchain(21, true, node, "10.0.0").verify(),
                    node);

            assertTrue(error.getMessage().contains("Node.js"), node);
            assertTrue(error.getMessage().contains(DevelopmentToolchain.NODE_REQUIREMENT), node);
        }
    }

    @Test
    void reportsEveryMissingOrOutdatedToolInOneFailure() {
        DevelopmentToolchain toolchain = new DevelopmentToolchain(
                () -> new DevelopmentToolchain.JavaInstallation("20.0.2", 20, false),
                command -> {
                    throw new IOException(command.getFirst() + " missing");
                });

        IllegalStateException error = assertThrows(IllegalStateException.class, toolchain::verify);

        assertTrue(error.getMessage().contains("Java 20.0.2 is too old"));
        assertTrue(error.getMessage().contains("javac was not found"));
        assertTrue(error.getMessage().contains("Node.js was not found"));
        assertTrue(error.getMessage().contains("npm was not found"));
        assertTrue(error.getMessage().contains("no project files were created"));
    }

    @Test
    void rejectsOldNpmAndMalformedVersionOutput() {
        IllegalStateException oldNpm = assertThrows(
                IllegalStateException.class,
                () -> toolchain(21, true, "v24.15.0", "9.99.9").verify());
        assertTrue(oldNpm.getMessage().contains("npm 9.99.9 is too old"));

        IllegalStateException malformed = assertThrows(
                IllegalStateException.class,
                () -> toolchain(21, true, "node version unknown", "10.0.0").verify());
        assertTrue(malformed.getMessage().contains("unrecognized version"));
    }

    @Test
    void reportsTimeoutAndNonZeroExit() {
        DevelopmentToolchain toolchain = new DevelopmentToolchain(
                () -> new DevelopmentToolchain.JavaInstallation("21.0.12", 21, true),
                command -> {
                    if (command.getFirst().startsWith("node")) {
                        throw new TimeoutException("node");
                    }
                    return new DevelopmentToolchain.CommandResult(7, "npm failed\n");
                });

        IllegalStateException error = assertThrows(IllegalStateException.class, toolchain::verify);

        assertTrue(error.getMessage().contains("Node.js version check timed out"));
        assertTrue(error.getMessage().contains("npm version check failed with exit code 7"));
    }

    private DevelopmentToolchain toolchain(
            int javaFeature,
            boolean compiler,
            String node,
            String npm) {
        Map<String, DevelopmentToolchain.CommandResult> results = new HashMap<>();
        results.put("node", new DevelopmentToolchain.CommandResult(0, node + "\r\n"));
        results.put("npm", new DevelopmentToolchain.CommandResult(0, npm + "\n"));
        results.put("npm.cmd", new DevelopmentToolchain.CommandResult(0, npm + "\n"));
        return new DevelopmentToolchain(
                () -> new DevelopmentToolchain.JavaInstallation(javaFeature + ".0.0", javaFeature, compiler),
                command -> result(results, command));
    }

    private DevelopmentToolchain.CommandResult result(
            Map<String, DevelopmentToolchain.CommandResult> results,
            List<String> command) throws IOException {
        DevelopmentToolchain.CommandResult result = results.get(command.getFirst());
        if (result == null) {
            throw new IOException("missing " + command.getFirst());
        }
        return result;
    }
}
