package com.wiz.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AngularBuildServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void skipsWhenAngularPackageIsMissingOrIncomplete() throws Exception {
        ProjectContext project = newProject();
        AngularBuildService service = new AngularBuildService(new FakeCommandExecutor());

        FrontendBuildResult missing = service.build(project);

        assertTrue(missing.success());
        assertTrue(missing.skipped());
        assertEquals("frontend-fallback", missing.phase());

        Path angularRoot = project.buildRoot().resolve("src/angular");
        Files.createDirectories(angularRoot);
        Files.writeString(angularRoot.resolve("package.json"), "{\"scripts\":{\"build\":\"ng build\"}}\n");
        Files.writeString(angularRoot.resolve("angular.json"), minimalAngularJson());

        FrontendBuildResult incomplete = service.build(project);

        assertTrue(incomplete.success());
        assertTrue(incomplete.skipped());
    }

    @Test
    void buildsReadyAngularPackageAndCopiesDist() throws Exception {
        ProjectContext project = newProject();
        Path angularRoot = project.buildRoot().resolve("src/angular");
        writeReadyAngularPackage(angularRoot);

        FakeCommandExecutor executor = new FakeCommandExecutor();
        FrontendBuildResult result = new AngularBuildService(executor).build(project);

        assertTrue(result.success());
        assertTrue(result.built());
        assertEquals(List.of("frontend-install", "frontend-build"), executor.phases);
        assertTrue(Files.exists(project.buildRoot().resolve("dist/build/index.html")));
    }

    @Test
    void supportsAngularObjectOutputPath() throws Exception {
        ProjectContext project = newProject();
        Path angularRoot = project.buildRoot().resolve("src/angular");
        writeReadyAngularPackage(angularRoot);
        Files.writeString(angularRoot.resolve("angular.json"), minimalAngularJsonWithObjectOutputPath());

        FakeCommandExecutor executor = new FakeCommandExecutor();
        FrontendBuildResult result = new AngularBuildService(executor).build(project);

        assertTrue(result.success(), result.message());
        assertTrue(result.built());
        assertTrue(Files.exists(project.buildRoot().resolve("dist/build/index.html")));
    }

    @Test
    void normalBuildSkipsNpmInstallWhenDependenciesExist() throws Exception {
        ProjectContext project = newProject();
        Path angularRoot = project.buildRoot().resolve("src/angular");
        writeReadyAngularPackage(angularRoot);
        Files.createDirectories(angularRoot.resolve("node_modules/pug"));

        FakeCommandExecutor executor = new FakeCommandExecutor();
        FrontendBuildResult result = new AngularBuildService(executor).build(project, false, BuildLogger.quiet());

        assertTrue(result.success());
        assertTrue(result.built());
        assertEquals(List.of("frontend-build"), executor.phases);
    }

    @Test
    void normalBuildFailsWithoutInstallingWhenDependenciesAreMissing() throws Exception {
        ProjectContext project = newProject();
        Path angularRoot = project.buildRoot().resolve("src/angular");
        writeReadyAngularPackage(angularRoot);
        delete(angularRoot.resolve("node_modules"));

        FakeCommandExecutor executor = new FakeCommandExecutor();
        FrontendBuildResult result = new AngularBuildService(executor).build(project, false, BuildLogger.quiet());

        assertFalse(result.success());
        assertTrue(result.message().contains("--clean"));
        assertTrue(executor.phases.isEmpty());
    }

    @Test
    void stagesWizComponentsAsValidAngularSources() throws Exception {
        ProjectContext project = newProject();
        new ProjectBuildService().reconstruct(project);

        FrontendBuildResult result = new AngularBuildService(new FakeCommandExecutor()).build(project);

        assertTrue(result.success(), result.message());
        Path component = project.buildRoot().resolve("src/angular/src/app/page.access/page.access.component.ts");
        String source = Files.readString(component);
        assertTrue(source.indexOf("import { OnInit } from '@angular/core';") < source.indexOf("@Component({"));
        assertTrue(source.indexOf("@Component({") < source.indexOf("export class PageAccessComponent"));
        assertTrue(source.contains("let wiz = new Wiz().app('page.access');"));

        String declarations = Files.readString(project.buildRoot().resolve("src/angular/src/types.d.ts"));
        assertFalse(declarations.contains("__WIZ_CONFIG__"));

        String index = Files.readString(project.buildRoot().resolve("src/angular/src/index.pug"));
        assertFalse(index.contains("config.js"));

        String runtimeConfig = Files.readString(project.buildRoot().resolve("src/angular/src/wiz-runtime-config.ts"));
        assertTrue(runtimeConfig.contains("WIZ_API_PREFIX = \"/wiz/api\""));
        assertTrue(runtimeConfig.contains("WIZ_SOCKET_PATH = \"/wiz/app\""));
        assertFalse(runtimeConfig.contains("WIZ_SOCKET_WEBSOCKET_PATH"));

        String routing = Files.readString(project.buildRoot().resolve("src/angular/src/app/app-routing.module.ts"));
        assertTrue(routing.contains("data: { app_id: \"page.access\" }"));
        assertFalse(routing.contains("component: PageAccessComponent, app_id:"));

        String service = Files.readString(project.buildRoot().resolve("src/angular/src/libs/portal/season/service.ts"));
        assertTrue(service.contains("public status: any;"));
    }

    private ProjectContext newProject() throws Exception {
        Path workspace = tempDir.resolve("workspace-" + java.util.UUID.randomUUID());
        new WorkspaceService().createWorkspace(workspace);
        return new ProjectService(new PathService(workspace)).createApp(null, null);
    }

    private void writeReadyAngularPackage(Path angularRoot) throws Exception {
        Files.createDirectories(angularRoot.resolve("src"));
        Files.createDirectories(angularRoot.resolve("node_modules/.bin"));
        Files.writeString(angularRoot.resolve("package.json"), "{\"scripts\":{\"build\":\"ng build\"}}\n");
        Files.writeString(angularRoot.resolve("angular.json"), minimalAngularJson());
        Files.writeString(angularRoot.resolve("src/index.html"), "<app-root></app-root>\n");
        Files.writeString(angularRoot.resolve("src/main.ts"), "console.log('main');\n");
        Files.writeString(angularRoot.resolve("tsconfig.app.json"), "{}\n");
        Files.writeString(angularRoot.resolve("node_modules/.bin/ng"), "#!/usr/bin/env node\n");
    }

    private void delete(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    private String minimalAngularJson() {
        return "{\n"
                + "  \"projects\": {\n"
                + "    \"build\": {\n"
                + "      \"architect\": {\n"
                + "        \"build\": {\n"
                + "          \"options\": {\n"
                + "            \"outputPath\": \"dist/build\",\n"
                + "            \"index\": \"src/index.html\",\n"
                + "            \"main\": \"src/main.ts\",\n"
                + "            \"tsConfig\": \"tsconfig.app.json\"\n"
                + "          }\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}\n";
    }

    private String minimalAngularJsonWithObjectOutputPath() {
        return "{\n"
                + "  \"projects\": {\n"
                + "    \"build\": {\n"
                + "      \"architect\": {\n"
                + "        \"build\": {\n"
                + "          \"options\": {\n"
                + "            \"outputPath\": { \"base\": \"dist/build\", \"browser\": \"\" },\n"
                + "            \"index\": \"src/index.html\",\n"
                + "            \"main\": \"src/main.ts\",\n"
                + "            \"tsConfig\": \"tsconfig.app.json\"\n"
                + "          }\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}\n";
    }

    private static final class FakeCommandExecutor extends CommandExecutor {
        private final ArrayList<String> phases = new ArrayList<>();

        @Override
        public CommandResult run(String phase, Path workspaceRoot, Path cwd, List<String> argv, Duration timeout, int outputCapBytes, BuildLogger logger) throws IOException {
            phases.add(phase);
            if (phase.equals("frontend-build")) {
                Path output = cwd.resolve("dist/build");
                Files.createDirectories(output);
                Files.writeString(output.resolve("index.html"), "<html></html>\n");
            }
            return new CommandResult(phase, argv, cwd, 0, 1, false, false, phase + " ok");
        }
    }
}
