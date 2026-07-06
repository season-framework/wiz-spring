package com.wiz.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.wiz.build.ProjectBuildService;
import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsSeasonYamlWithoutCorePortalDefaults() throws Exception {
        ProjectContext project = createApp();
        Files.writeString(project.configRoot().resolve("season.yml"), "auth_baseuri: /custom-auth\nsmtp_port: 2525\n");
        new ProjectBuildService().build(project, true, "bundle");

        ConfigNamespace config = new ConfigService(project).namespace("season");

        assertEquals("/custom-auth", config.get("auth_baseuri"));
        assertEquals("2525", String.valueOf(config.get("smtp_port")));
    }

    @Test
    void acceptsLegacyHyphenatedAuthBaseUriAlias() throws Exception {
        ProjectContext project = createApp();
        Files.writeString(project.configRoot().resolve("season.yml"), "auth-base-uri: /legacy-auth\n");
        new ProjectBuildService().build(project, true, "bundle");

        ConfigNamespace config = new ConfigService(project).namespace("season");

        assertEquals("/legacy-auth", config.get("auth_baseuri"));
    }

    @Test
    void mergesWorkspaceAndBundleConfigInOverrideOrder() throws Exception {
        ProjectContext project = createApp();
        Files.writeString(project.configRoot().resolve("season.yml"), "pwa_title: Workspace Title\nsmtp_port: 1025\n");
        new ProjectBuildService().build(project, true, "bundle");
        Files.writeString(project.bundleRoot().resolve("config/season.yml"), "auth_baseuri: /bundle-auth\nsmtp_port: 2525\n");

        ConfigNamespace config = new ConfigService(project).namespace("season");

        assertEquals("Workspace Title", config.get("pwa_title"));
        assertEquals("/bundle-auth", config.get("auth_baseuri"));
        assertEquals("2525", String.valueOf(config.get("smtp_port")));
    }

    @Test
    void validatesKeysOnlyWhenCallerProvidesDefaults() throws Exception {
        ProjectContext project = createApp();
        Files.writeString(project.configRoot().resolve("season.yml"), "unknown_key: true\n");
        new ProjectBuildService().build(project, true, "bundle");

        assertThrows(IllegalArgumentException.class,
                () -> new ConfigService(project).namespace("season", Map.of("auth_baseuri", "/auth")));
    }

    @Test
    void ignoresPythonConfigFiles() throws Exception {
        ProjectContext project = createApp();
        Files.deleteIfExists(project.configRoot().resolve("season.yml"));
        Files.writeString(project.configRoot().resolve("season.py"), "raise RuntimeError('must not execute')\n");
        new ProjectBuildService().build(project, true, "bundle");

        ConfigNamespace config = new ConfigService(project).namespace("season", Map.of("auth_baseuri", "/auth"));

        assertEquals("/auth", config.get("auth_baseuri"));
    }

    @Test
    void exposesConfigServiceFromWizContext() throws Exception {
        ProjectContext project = createApp();
        new ProjectBuildService().build(project, true, "bundle");

        try (WizContext context = new WizContext(WizRequest.builder().build(), new WizResponse(), project)) {
            assertEquals("/auth", context.config().namespace("season").get("auth_baseuri"));
        }
    }

    @Test
    void cachesConfigFileValuesInsideCachedProjectRuntime() throws Exception {
        ProjectContext project = createApp();
        Files.writeString(project.configRoot().resolve("season.yml"), "auth_baseuri: /one\n");
        new ProjectBuildService().build(project, true, "bundle");
        ProjectRuntimeCache cache = new ProjectRuntimeCache();
        ProjectRuntimeCache.CachedProjectRuntime runtime = cache.get(project);
        ConfigService config = new ConfigService(project, runtime);

        assertEquals("/one", config.namespace("season").get("auth_baseuri"));
        Files.writeString(project.configRoot().resolve("season.yml"), "auth_baseuri: /two\n");
        Files.writeString(project.bundleRoot().resolve("config/season.yml"), "auth_baseuri: /two\n");

        assertEquals("/one", config.namespace("season").get("auth_baseuri"));

        cache.invalidate(project);
        ConfigService refreshed = new ConfigService(project, cache.get(project));
        assertEquals("/two", refreshed.namespace("season").get("auth_baseuri"));
    }

    @Test
    void refreshesCachedProjectRuntimeAfterRebuildChangesConfig() throws Exception {
        ProjectContext project = createApp();
        Path configFile = project.configRoot().resolve("season.yml");
        Files.writeString(configFile, "auth_baseuri: /one\n");
        new ProjectBuildService().build(project, true, "bundle");
        ProjectRuntimeCache cache = new ProjectRuntimeCache();

        try {
            ProjectRuntimeCache.CachedProjectRuntime first = cache.get(project);
            assertEquals("/one", new ConfigService(project, first).namespace("season").get("auth_baseuri"));

            Files.writeString(configFile, "auth_baseuri: /two\n");
            new ProjectBuildService().build(project, true, "bundle");
            ProjectRuntimeCache.CachedProjectRuntime second = cache.get(project);

            assertNotSame(first, second);
            assertEquals("/two", new ConfigService(project, second).namespace("season").get("auth_baseuri"));
        } finally {
            cache.close();
        }
    }

    private ProjectContext createApp() throws Exception {
        Path workspace = tempDir.resolve("workspace-" + java.util.UUID.randomUUID());
        new WorkspaceService().createWorkspace(workspace);
        return new ProjectService(new PathService(workspace)).createApp(null, null);
    }
}
