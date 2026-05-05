package com.wiz.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.wiz.build.ProjectBuildService;
import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RouteRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void scansBundleRouteMetadataAndCanonicalizesPortalRouteIds() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        Files.createDirectories(project.sourceRoot().resolve("portal/season"));
        Files.writeString(project.sourceRoot().resolve("portal/season/portal.json"), "{\"use_route\":true}\n");
        Path routeRoot = project.sourceRoot().resolve("portal/season/route/auth");
        Files.createDirectories(routeRoot);
        Files.writeString(routeRoot.resolve("app.json"), "{\n"
                + "  \"id\": \"auth\",\n"
                + "  \"title\": \"/auth/<path:path>\",\n"
                + "  \"route\": \"/auth/<path:path>\",\n"
                + "  \"controller\": \"base\"\n"
                + "}\n");
        new ProjectBuildService().build(project, true, "bundle");

        RouteDefinition auth = new RouteRegistry().definitions(project).stream()
                .filter(definition -> definition.route().equals("/auth/<path:path>"))
                .findFirst()
                .orElseThrow();

        assertEquals("portal.season.auth", auth.id());
        assertEquals("portal/season/base", auth.controllerName());
        assertTrue(auth.acceptsMethod("GET"));
    }

    @Test
    void defaultsBlankRouteControllerToBase() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        Path routeRoot = project.sourceRoot().resolve("route/ping");
        Files.createDirectories(routeRoot);
        Files.writeString(routeRoot.resolve("app.json"), "{\n"
                + "  \"id\": \"ping\",\n"
                + "  \"route\": \"/ping\",\n"
                + "  \"controller\": \"\"\n"
                + "}\n");
        new ProjectBuildService().build(project, true, "bundle");

        RouteDefinition ping = new RouteRegistry().definitions(project).stream()
                .filter(definition -> definition.id().equals("ping"))
                .findFirst()
                .orElseThrow();

        assertEquals("base", ping.controllerName());
    }
}