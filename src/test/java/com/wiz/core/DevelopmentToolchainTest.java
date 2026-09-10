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
    void acceptsTheDependencySupportedNodeLinesAndNpmEightOrNewer() {
        for (String node : List.of(
                "v22.22.3", "v22.23.2", "v24.15.0", "v24.20.0", "v26.0.0", "v28.1.0")) {
            DevelopmentToolchain toolchain = toolchain(25, true, node, "8.0.0");
            DevelopmentToolchain.Report report = toolchain.verify();

            assertEquals(node.substring(1), report.nodeVersion());
            assertEquals("8.0.0", report.npmVersion());
            assertTrue(report.warnings().isEmpty());
        }
    }

    @Test
    void warnsWithoutBlockingForNodeAndNpmOutsideTheGeneratedProjectRange() {
        for (String node : List.of(
                "v20.19.0", "v22.22.2", "v23.9.0", "v24.14.9", "v25.0.0", "v24.15.0-rc.1")) {
            DevelopmentToolchain.Report report = toolchain(25, true, node, "7.99.9").verify();

            assertTrue(report.warnings().stream().anyMatch(warning -> warning.contains("Node.js")), node);
            assertTrue(report.warnings().stream().anyMatch(warning -> warning.contains("npm 7.99.9")), node);
        }
    }

    @Test
    void onlyJavaProblemsBlockProjectCreation() {
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
    void missingAndMalformedFrontendToolsAreAdvisory() {
        DevelopmentToolchain missing = new DevelopmentToolchain(
                () -> new DevelopmentToolchain.JavaInstallation("25.0.1", 25, true),
                command -> {
                    throw new IOException(command.getFirst() + " missing");
                });
        DevelopmentToolchain.Report missingReport = missing.verify();
        assertEquals("not available", missingReport.nodeVersion());
        assertEquals("not available", missingReport.npmVersion());
        assertTrue(missingReport.warnings().stream().anyMatch(warning -> warning.contains("Node.js was not found")));
        assertTrue(missingReport.warnings().stream().anyMatch(warning -> warning.contains("npm was not found")));

        DevelopmentToolchain.Report malformed = toolchain(
                25, true, "node version unknown", "10.0.0").verify();
        assertEquals("not available", malformed.nodeVersion());
        assertTrue(malformed.warnings().stream().anyMatch(warning -> warning.contains("unrecognized version")));
    }

    @Test
    void reportsTimeoutAndNonZeroExitAsWarnings() {
        DevelopmentToolchain toolchain = new DevelopmentToolchain(
                () -> new DevelopmentToolchain.JavaInstallation("25.0.1", 25, true),
                command -> {
                    if (command.getFirst().startsWith("node")) {
                        throw new TimeoutException("node");
                    }
                    return new DevelopmentToolchain.CommandResult(7, "npm failed\n");
                });

        DevelopmentToolchain.Report report = toolchain.verify();

        assertTrue(report.warnings().stream().anyMatch(warning -> warning.contains("Node.js version check timed out")));
        assertTrue(report.warnings().stream().anyMatch(warning -> warning.contains("npm version check failed with exit code 7")));
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
