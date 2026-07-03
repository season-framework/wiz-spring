package com.wiz.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.wiz.build.BuildResult;
import com.wiz.build.ProjectBuildService;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectScaffoldServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createsCliManagedSourceSkeletonsThatBuild() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        ProjectScaffoldService service = new ProjectScaffoldService(new PathService(workspace));

        service.createApp("main", null, "page.cli", "pug", "page");
        service.createController("main", null, "cli");
        service.createRoute("main", null, "custom", "/api/v1", "GET,POST");
        service.createPackage("main", "blog");

        assertTrue(Files.exists(project.appRoot().resolve("page.cli/app.json")));
        assertTrue(Files.exists(project.appRoot().resolve("page.cli/view.pug")));
        assertTrue(Files.exists(project.appRoot().resolve("page.cli/api.java")));
        assertTrue(Files.exists(project.sourceRoot().resolve("controller/CliController.java")));
        assertTrue(Files.exists(project.routeRoot().resolve("custom/app.json")));
        assertTrue(Files.exists(project.routeRoot().resolve("custom/route.java")));
        assertTrue(Files.exists(project.sourceRoot().resolve("portal/blog/portal.json")));
        assertTrue(service.listApps("main", null).contains("page.cli"));
        assertTrue(service.listControllers("main", null).contains("CliController.java"));
        assertTrue(service.listRoutes("main", null).contains("custom"));
        assertTrue(service.listPackages("main").contains("blog"));

        BuildResult result = new ProjectBuildService().build(project, true, "bundle");
        assertTrue(result.success(), result.message());
        assertTrue(Files.exists(project.bundleRoot().resolve("app-api.jar")));

        service.deleteApp("main", null, "page.cli");
        service.deleteController("main", null, "cli");
        service.deleteRoute("main", null, "custom");
        service.deletePackage("main", "blog");
        assertTrue(!Files.exists(project.appRoot().resolve("page.cli")));
        assertTrue(!Files.exists(project.sourceRoot().resolve("controller/CliController.java")));
        assertTrue(!Files.exists(project.routeRoot().resolve("custom")));
        assertTrue(!Files.exists(project.sourceRoot().resolve("portal/blog")));
    }

    @Test
    void listsNpmDependenciesFromAngularPackageJson() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        Path angular = project.sourceRoot().resolve("angular");
        Files.createDirectories(angular);
        Files.writeString(angular.resolve("package.json"), """
                {
                  "dependencies": {
                    "lodash": "^4.17.21"
                  },
                  "devDependencies": {
                    "typescript": "~5.9.3"
                  }
                }
                """);

        Map<String, Object> data = new ProjectScaffoldService(new PathService(workspace)).npmList("main");

        assertEquals("^4.17.21", ((Map<?, ?>) data.get("dependencies")).get("lodash"));
        assertEquals("~5.9.3", ((Map<?, ?>) data.get("devDependencies")).get("typescript"));
    }
}
