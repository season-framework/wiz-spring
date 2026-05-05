package com.wiz.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.wiz.build.ProjectBuildService;
import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.domain.ModelRegistry;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizRequest;
import com.wiz.runtime.WizResponse;
import com.wiz.session.SeasonConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeasonPortalModuleTest {

    @TempDir
    Path tempDir;

    @Test
    void exposesSeasonConfigPwaAndSmtpModels() throws Exception {
        ProjectContext project = createProject();
        new ProjectBuildService().build(project, true, "bundle");
        ModelRegistry models = new ModelRegistry(List.of(
                new SeasonConfigModelProvider(),
                new PwaModelProvider(),
                new SmtpModelProvider()));

        try (WizContext context = new WizContext(WizRequest.builder().build(), new WizResponse(), project, models)) {
            SeasonConfig config = context.models().get(SeasonPortalModule.CONFIG_MODEL, SeasonConfig.class);
            PwaService pwa = context.models().get(SeasonPortalModule.PWA_MODEL, PwaService.class);
            SmtpService smtp = context.models().get(SeasonPortalModule.SMTP_MODEL, SmtpService.class);

            assertEquals(config.pwaTitle(), pwa.manifest().get("name"));
            assertEquals(6, smtp.randomCode().length());
        }
    }

    @Test
    void readsPwaServiceWorkerFromProjectConfig() throws Exception {
        ProjectContext project = createProject();
        Files.createDirectories(project.configRoot().resolve("pwa"));
        Files.writeString(project.configRoot().resolve("pwa/sw.js"), "self.__wiz = true;\n");
        new ProjectBuildService().build(project, true, "bundle");
        SeasonPortalModule module = new SeasonPortalModule(new PathService(project.root().getParent().getParent()));

        assertEquals("self.__wiz = true;\n", module.serviceWorker(Map.of()));
        assertTrue(module.manifest(Map.of()).orElseThrow().containsKey("icons"));
    }

    private ProjectContext createProject() throws Exception {
        Path workspace = tempDir.resolve("workspace-" + java.util.UUID.randomUUID());
        new WorkspaceService().createWorkspace(workspace);
        return new ProjectService(new PathService(workspace)).createProject("main", null, null);
    }
}