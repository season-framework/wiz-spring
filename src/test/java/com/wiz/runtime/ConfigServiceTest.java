package com.wiz.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        ProjectContext project = createProject();
        Files.writeString(project.configRoot().resolve("season.yml"), "auth_baseuri: /custom-auth\nsmtp_port: 2525\n");
        new ProjectBuildService().build(project, true, "bundle");

        ConfigNamespace config = new ConfigService(project).namespace("season");

        assertEquals("/custom-auth", config.get("auth_baseuri"));
        assertEquals("2525", String.valueOf(config.get("smtp_port")));
    }

    @Test
    void acceptsLegacyHyphenatedAuthBaseUriAlias() throws Exception {
        ProjectContext project = createProject();
        Files.writeString(project.configRoot().resolve("season.yml"), "auth-base-uri: /legacy-auth\n");
        new ProjectBuildService().build(project, true, "bundle");

        ConfigNamespace config = new ConfigService(project).namespace("season");

        assertEquals("/legacy-auth", config.get("auth_baseuri"));
    }

    @Test
    void mergesWorkspaceProjectAndBundleConfigInOverrideOrder() throws Exception {
        ProjectContext project = createProject();
        Path workspaceConfig = project.root().getParent().getParent().resolve("config");
        Files.writeString(workspaceConfig.resolve("season.yml"), "pwa_title: Workspace Title\nsmtp_port: 1025\n");
        Files.writeString(project.configRoot().resolve("season.yml"), "auth_baseuri: /project-auth\nsmtp_port: 2525\n");
        new ProjectBuildService().build(project, true, "bundle");

        ConfigNamespace config = new ConfigService(project).namespace("season");

        assertEquals("Workspace Title", config.get("pwa_title"));
        assertEquals("/project-auth", config.get("auth_baseuri"));
        assertEquals("2525", String.valueOf(config.get("smtp_port")));
    }

    @Test
    void validatesKeysOnlyWhenCallerProvidesDefaults() throws Exception {
        ProjectContext project = createProject();
        Files.writeString(project.configRoot().resolve("season.yml"), "unknown_key: true\n");
        new ProjectBuildService().build(project, true, "bundle");

        assertThrows(IllegalArgumentException.class,
                () -> new ConfigService(project).namespace("season", Map.of("auth_baseuri", "/auth")));
    }

    @Test
    void ignoresPythonConfigFiles() throws Exception {
        ProjectContext project = createProject();
        Files.deleteIfExists(project.configRoot().resolve("season.yml"));
        Files.writeString(project.configRoot().resolve("season.py"), "raise RuntimeError('must not execute')\n");
        new ProjectBuildService().build(project, true, "bundle");

        ConfigNamespace config = new ConfigService(project).namespace("season", Map.of("auth_baseuri", "/auth"));

        assertEquals("/auth", config.get("auth_baseuri"));
    }

    @Test
    void exposesConfigServiceFromWizContext() throws Exception {
        ProjectContext project = createProject();
        new ProjectBuildService().build(project, true, "bundle");

        try (WizContext context = new WizContext(WizRequest.builder().build(), new WizResponse(), project)) {
            assertEquals("/auth", context.config().namespace("season").get("auth_baseuri"));
        }
    }

    private ProjectContext createProject() throws Exception {
        Path workspace = tempDir.resolve("workspace-" + java.util.UUID.randomUUID());
        new WorkspaceService().createWorkspace(workspace);
        return new ProjectService(new PathService(workspace)).createProject("main", null, null);
    }
}
