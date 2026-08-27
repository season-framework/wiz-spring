package com.wiz.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevProxyTemplateTest {

    @TempDir
    Path tempDir;

    @Test
    void generatedProxyUsesDynamicPrefixWithSegmentBoundariesAndReservedRoutes() throws Exception {
        Path helper = tempDir.resolve("dev-proxy.cjs");
        try (InputStream input = getClass().getResourceAsStream(
                "/wiz/templates/project-common/scripts/lib/dev-proxy.cjs")) {
            assertNotNull(input);
            Files.copy(input, helper);
        }

        Assumptions.assumeTrue(nodeIsAvailable(), "Node.js is not available");
        String assertions = """
                const assert = require('node:assert/strict');
                const { createDevProxy, normalizeApiPrefix } = require(process.argv[1]);

                assert.equal(normalizeApiPrefix(null), '/api');
                assert.equal(normalizeApiPrefix('  /gateway/v2  '), '/gateway/v2');
                for (const invalid of ['api', '/', '/api/', '/api//v2', '/api/*', '/api?x=1']) {
                    assert.throws(() => normalizeApiPrefix(invalid), /absolute path/);
                }

                process.env.APP_API_PREFIX = '/api/v2';
                const proxy = createDevProxy();
                const patterns = Object.keys(proxy).map(pattern => new RegExp(pattern));
                const matches = path => patterns.some(pattern => pattern.test(path));
                for (const path of [
                    '/api/v2', '/api/v2?trace=true', '/api/v2/dashboard',
                    '/app-config.json', '/app-config.json?reload=1',
                    '/v3/api-docs', '/v3/api-docs/swagger-config', '/v3/api-docs.yaml?format=json',
                    '/swagger-ui', '/swagger-ui/index.html', '/swagger-ui.html',
                    '/actuator', '/actuator/health', '/actuator/health?details=true'
                ]) assert.equal(matches(path), true, path);
                for (const path of [
                    '/api/v20', '/api/v2x', '/app-config.jsonx', '/v3/api-docsx',
                    '/swagger-uix', '/actuatorx', '/dashboard'
                ]) assert.equal(matches(path), false, path);
                """;

        Process process = new ProcessBuilder("node", "-e", assertions, helper.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Node.js proxy test timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
    }

    @Test
    void angularAndReactDevelopmentServersLoadTheSharedProxyFactory() throws Exception {
        String angularPackage = resource("/wiz/templates/project-angular/package.json");
        String reactPackage = resource("/wiz/templates/project-react/package.json");
        String angularProxy = resource("/wiz/templates/project-angular/proxy.conf.cjs");
        String reactVite = resource("/wiz/templates/project-react/vite.config.js");

        assertTrue(angularPackage.contains("ng serve --proxy-config proxy.conf.cjs"));
        assertTrue(reactPackage.contains("vite --config vite.config.js"));
        assertTrue(angularProxy.contains("createDevProxy"));
        assertTrue(reactVite.contains("createDevProxy()"));
        assertTrue(reactVite.contains("./scripts/lib/dev-proxy.cjs"));
    }

    @Test
    void frontendDetectionUsesPackageMetadataAndRejectsLayoutMismatches() throws Exception {
        Assumptions.assumeTrue(nodeIsAvailable(), "Node.js is not available");

        assertDetection("html", "{\"wiz\":{\"frontend\":\"html\"}}", List.of("frontend"), 0, "html");
        assertDetection("jsp", "{\"wiz\":{\"frontend\":\"jsp\"}}",
                List.of("src/main/webapp/WEB-INF"), 0, "jsp");
        assertDetection("react", "{\"dependencies\":{\"react\":\"1\"}}",
                List.of("frontend"), 0, "react");
        assertDetection("angular", "{\"wiz\":{\"frontend\":\"angular\"}}",
                List.of("angular.json"), 0, "angular");
        assertDetection("angular-wiz", "{\"wiz\":{\"frontend\":\"angular-wiz\"},"
                        + "\"dependencies\":{\"@angular/core\":\"1\"}}",
                List.of("angular.json", "src/app", "src/angular"), 0, "angular-wiz");
        assertDetection("mismatch", "{\"wiz\":{\"frontend\":\"react\"}}",
                List.of("frontend"), 1, "does not match project structure 'html'");
    }

    private void assertDetection(
            String fixtureName,
            String packageJson,
            List<String> evidence,
            int expectedExit,
            String expectedOutput) throws Exception {
        Path root = tempDir.resolve("detect-" + fixtureName);
        Path helper = root.resolve("scripts/lib/project.mjs");
        Files.createDirectories(helper.getParent());
        try (InputStream input = getClass().getResourceAsStream(
                "/wiz/templates/project-common/scripts/lib/project.mjs")) {
            assertNotNull(input);
            Files.copy(input, helper);
        }
        Files.writeString(root.resolve("package.json"), packageJson);
        for (String relative : evidence) {
            Path item = root.resolve(relative);
            if (relative.endsWith(".json")) {
                Files.createDirectories(item.getParent());
                Files.writeString(item, "{}\n");
            } else {
                Files.createDirectories(item);
            }
        }

        String script = """
                import { pathToFileURL } from 'node:url';
                try {
                  const module = await import(pathToFileURL(process.argv[1]).href);
                  console.log(await module.detectFrontend());
                } catch (error) {
                  console.error(error.message);
                  process.exitCode = 1;
                }
                """;
        Process process = new ProcessBuilder(
                "node", "--input-type=module", "-e", script, helper.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "frontend detection timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(expectedExit, process.exitValue(), output);
        assertTrue(output.contains(expectedOutput), output);
    }

    private String resource(String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(name)) {
            assertNotNull(input, name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private boolean nodeIsAvailable() throws InterruptedException {
        try {
            Process process = new ProcessBuilder("node", "--version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        }
    }
}
