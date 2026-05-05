package com.wiz.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.runtime.BuildMarkerService;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectBuildServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void reconstructsSourceTreeAndFlattensPortalApps() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        Files.createDirectories(project.sourceRoot().resolve("portal/post"));
        Files.writeString(project.sourceRoot().resolve("portal/post/portal.json"), "{\"use_app\":true,\"use_route\":true}\n");
        Files.createDirectories(project.sourceRoot().resolve("portal/post/app/list"));
        Files.writeString(project.sourceRoot().resolve("portal/post/app/list/app.json"), "{\"controller\":\"guard\"}\n");
        Files.createDirectories(project.sourceRoot().resolve("portal/post/route/auth"));
        Files.writeString(project.sourceRoot().resolve("portal/post/route/auth/app.json"), "{\"controller\":\"guard\"}\n");

        BuildResult result = new ProjectBuildService().build(project, true, "reconstruct");

        assertTrue(result.success(), result.message());
        assertEquals(java.util.List.of("reconstruct"), result.phases());
        assertTrue(Files.exists(project.buildRoot().resolve("src/app/page.dashboard/api.java")));
        assertTrue(Files.exists(project.buildRoot().resolve("src/app/portal.post.list/app.json")));
        assertTrue(Files.exists(project.buildRoot().resolve("src/route/portal.post.auth/app.json")));
        String appJson = Files.readString(project.buildRoot().resolve("src/app/portal.post.list/app.json"));
        assertTrue(appJson.contains("\"id\" : \"portal.post.list\""));
        assertTrue(appJson.contains("\"mode\" : \"portal\""));
        assertTrue(appJson.contains("\"controller\" : \"portal/post/guard\""));
    }

    @Test
    void portalFlagsControlFlattenedBuildInputs() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        Files.createDirectories(project.sourceRoot().resolve("portal/post"));
        Files.writeString(project.sourceRoot().resolve("portal/post/portal.json"), "{\"use_app\":false,\"use_route\":true,\"use_controller\":true,\"use_model\":true,\"use_assets\":false}\n");
        Files.createDirectories(project.sourceRoot().resolve("portal/post/app/list"));
        Files.writeString(project.sourceRoot().resolve("portal/post/app/list/app.json"), "{}\n");
        Files.createDirectories(project.sourceRoot().resolve("portal/post/route/auth"));
        Files.writeString(project.sourceRoot().resolve("portal/post/route/auth/app.json"), "{}\n");
        Files.createDirectories(project.sourceRoot().resolve("portal/post/controller"));
        Files.writeString(project.sourceRoot().resolve("portal/post/controller/GuardController.java"), guardControllerJava());
        Files.createDirectories(project.sourceRoot().resolve("portal/post/model"));
        Files.writeString(project.sourceRoot().resolve("portal/post/model/PostStruct.java"), "public final class PostStruct {}\n");
        Files.createDirectories(project.sourceRoot().resolve("portal/post/assets"));
        Files.writeString(project.sourceRoot().resolve("portal/post/assets/logo.txt"), "logo\n");

        BuildResult result = new ProjectBuildService().build(project, true, "reconstruct");

        assertTrue(result.success());
        assertTrue(Files.notExists(project.buildRoot().resolve("src/app/portal.post.list/app.json")));
        assertTrue(Files.exists(project.buildRoot().resolve("src/route/portal.post.auth/app.json")));
        assertTrue(Files.exists(project.buildRoot().resolve("src/controller/portal/post/GuardController.java")));
        assertTrue(Files.exists(project.buildRoot().resolve("src/model/portal/post/PostStruct.java")));
        assertTrue(Files.notExists(project.buildRoot().resolve("src/assets/portal/post/logo.txt")));
    }

    @Test
    void normalizesAppAndRouteMetadataDefaults() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        Files.createDirectories(project.appRoot().resolve("custom.echo"));
        Files.writeString(project.appRoot().resolve("custom.echo/app.json"), "{\"controller\":\"\",\"viewuri\":\"/echo\"}\n");
        Files.writeString(project.appRoot().resolve("custom.echo/api.java"), "public final class CustomEchoApi {}\n");
        Files.writeString(project.appRoot().resolve("custom.echo/socket.java"), "public final class CustomEchoSocketController {}\n");
        Files.createDirectories(project.routeRoot().resolve("custom.api"));
        Files.writeString(project.routeRoot().resolve("custom.api/app.json"), "{}\n");

        BuildResult result = new ProjectBuildService().build(project, true, "reconstruct");

        assertTrue(result.success());
        String appJson = Files.readString(project.buildRoot().resolve("src/app/custom.echo/app.json"));
        assertTrue(appJson.contains("\"id\" : \"custom.echo\""));
        assertTrue(appJson.contains("\"mode\" : \"app\""));
        assertTrue(appJson.contains("\"controller\" : \"base\""));
        assertTrue(appJson.contains("\"path\" : \"./custom.echo/custom.echo.component\""));
        assertTrue(appJson.contains("\"template\" : \"wiz-custom-echo()\""));
        assertTrue(appJson.contains("\"handler\" : \"com.wiz.project.main.api.CustomEchoApi\""));
        assertTrue(appJson.contains("\"handler\" : \"com.wiz.project.main.socket.CustomEchoSocketController\""));

        String routeJson = Files.readString(project.buildRoot().resolve("src/route/custom.api/app.json"));
        assertTrue(routeJson.contains("\"id\" : \"custom.api\""));
        assertTrue(routeJson.contains("\"route\" : \"/custom/api\""));
        assertTrue(routeJson.contains("\"path\" : \"/custom/api\""));
        assertTrue(routeJson.contains("\"controller\" : \"base\""));
        assertTrue(routeJson.contains("\"handler\" : \"com.wiz.project.main.route.CustomApiRouteHandler\""));
    }

    @Test
    void rejectsUnsupportedBuildPhase() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);

        BuildResult result = new ProjectBuildService().build(project, false, "full");

        assertEquals(2, result.exitCode());
    }

    @Test
    void compilesAppLocalJavaApiAndCreatesBundle() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        removeAngularSource(project);

        BuildResult result = new ProjectBuildService().build(project, true, "bundle");

        assertTrue(result.success());
        assertEquals(java.util.List.of("reconstruct", "java-source", "project-dependencies", "java-compile", "frontend-fallback", "bundle"), result.phases());
        assertTrue(Files.exists(project.buildRoot().resolve("main/java/com/wiz/project/main/api/PageDashboardApi.java")));
        assertTrue(Files.exists(project.buildRoot().resolve("classes/com/wiz/project/main/api/PageDashboardApi.class")));
        assertTrue(Files.exists(project.buildRoot().resolve("project-api.jar")));
        assertTrue(Files.exists(project.bundleRoot().resolve("project-api.jar")));
        assertTrue(Files.exists(project.bundleRoot().resolve("classes/com/wiz/project/main/api/PageDashboardApi.class")));
        assertTrue(Files.exists(project.bundleRoot().resolve("src/app/page.dashboard/api.java")));
        assertTrue(Files.exists(project.bundleWwwRoot().resolve("index.html")));
        assertTrue(Files.exists(project.bundleWwwRoot().resolve("app.js")));
        String marker = Files.readString(project.bundleRoot().resolve(BuildMarkerService.MARKER_FILE));
        assertTrue(marker.contains("\"frontendMode\" : \"fallback\""));
        assertTrue(marker.contains("\"buildPhases\""));
    }

    @Test
    void compilesProjectControllerJavaSources() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        removeAngularSource(project);
        Files.writeString(project.sourceRoot().resolve("controller/GuardController.java"), guardControllerJava());

        BuildResult result = new ProjectBuildService().build(project, true, "bundle");

        assertTrue(result.success());
        assertTrue(Files.exists(project.buildRoot().resolve("main/java/com/wiz/project/main/controller/GuardController.java")));
        assertTrue(Files.exists(project.buildRoot().resolve("classes/com/wiz/project/main/controller/GuardController.class")));
        assertTrue(Files.exists(project.bundleRoot().resolve("classes/com/wiz/project/main/controller/GuardController.class")));
    }

    @Test
    void compilesAppLocalSocketJavaSources() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        removeAngularSource(project);
        Files.writeString(project.appRoot().resolve("page.dashboard/socket.java"), dashboardSocketJava());
        Files.createDirectories(project.sourceRoot().resolve("portal/post"));
        Files.writeString(project.sourceRoot().resolve("portal/post/portal.json"), "{\"use_app\":true,\"use_model\":true}\n");
        Files.createDirectories(project.sourceRoot().resolve("portal/post/app/list"));
        Files.writeString(project.sourceRoot().resolve("portal/post/app/list/app.json"), "{}\n");
        Files.writeString(project.sourceRoot().resolve("portal/post/app/list/socket.java"), portalSocketJava());

        BuildResult result = new ProjectBuildService().build(project, true, "bundle");

        assertTrue(result.success(), result.message());
        assertTrue(Files.exists(project.buildRoot().resolve("main/java/com/wiz/project/main/socket/PageDashboardSocketController.java")));
        assertTrue(Files.exists(project.buildRoot().resolve("classes/com/wiz/project/main/socket/PageDashboardSocketController.class")));
        assertTrue(Files.exists(project.buildRoot().resolve("classes/com/wiz/project/main/socket/PortalPostListSocketController.class")));
        assertTrue(Files.exists(project.bundleRoot().resolve("classes/com/wiz/project/main/socket/PageDashboardSocketController.class")));
    }

    @Test
    void compilesRouteLocalJavaSources() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        removeAngularSource(project);
        Files.createDirectories(project.routeRoot().resolve("custom.echo"));
        Files.writeString(project.routeRoot().resolve("custom.echo/app.json"), "{\"id\":\"custom.echo\",\"route\":\"/echo/<name>\"}\n");
        Files.writeString(project.routeRoot().resolve("custom.echo/route.java"), echoRouteJava());

        BuildResult result = new ProjectBuildService().build(project, true, "bundle");

        assertTrue(result.success());
        assertTrue(Files.exists(project.buildRoot().resolve("main/java/com/wiz/project/main/route/CustomEchoRouteHandler.java")));
        assertTrue(Files.exists(project.buildRoot().resolve("classes/com/wiz/project/main/route/CustomEchoRouteHandler.class")));
        assertTrue(Files.exists(project.bundleRoot().resolve("classes/com/wiz/project/main/route/CustomEchoRouteHandler.class")));
    }

    @Test
    void compilesProjectModelAndPortalModelJavaSources() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        removeAngularSource(project);
        removeJavaSources(project);
        Files.writeString(project.modelRoot().resolve("Struct.java"), "public final class Struct {}\n");
        Files.createDirectories(project.modelRoot().resolve("struct"));
        Files.writeString(project.modelRoot().resolve("struct/UserStruct.java"), "public final class UserStruct {}\n");
        Files.createDirectories(project.sourceRoot().resolve("portal/post"));
        Files.writeString(project.sourceRoot().resolve("portal/post/portal.json"), "{\"use_model\":true}\n");
        Files.createDirectories(project.sourceRoot().resolve("portal/post/model/struct"));
        Files.writeString(project.sourceRoot().resolve("portal/post/model/PostStruct.java"), "public final class PostStruct {}\n");
        Files.writeString(project.sourceRoot().resolve("portal/post/model/struct/PostService.java"), "public final class PostService {}\n");

        BuildResult result = new ProjectBuildService().build(project, true, "bundle");

        assertTrue(result.success());
        assertTrue(Files.exists(project.buildRoot().resolve("classes/com/wiz/project/main/model/Struct.class")));
        assertTrue(Files.exists(project.buildRoot().resolve("classes/com/wiz/project/main/model/struct/UserStruct.class")));
        assertTrue(Files.exists(project.buildRoot().resolve("classes/com/wiz/project/main/portal/post/model/PostStruct.class")));
        assertTrue(Files.exists(project.bundleRoot().resolve("classes/com/wiz/project/main/portal/post/model/struct/PostService.class")));
    }

    private void removeJavaSources(ProjectContext project) throws Exception {
        try (var paths = Files.walk(project.sourceRoot())) {
            for (Path source : paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java")).toList()) {
                Files.delete(source);
            }
        }
    }

    private void removeAngularSource(ProjectContext project) throws Exception {
        Path angular = project.sourceRoot().resolve("angular");
        if (!Files.exists(angular)) {
            return;
        }
        try (var paths = Files.walk(angular)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private String guardControllerJava() {
        return "import com.wiz.dispatch.ControllerHook;\n"
                + "import com.wiz.runtime.WizContext;\n"
                + "import com.wiz.runtime.WizResult;\n"
                + "import java.util.Map;\n\n"
                + "public final class GuardController implements ControllerHook {\n"
                + "    public WizResult before(WizContext wiz) {\n"
                + "        return wiz.response().status(401, Map.of(\"error\", \"blocked\"));\n"
                + "    }\n"
                + "}\n";
    }

    private String dashboardSocketJava() {
        return "import com.wiz.socket.SocketController;\n"
                + "import com.wiz.socket.SocketEventHandler;\n"
                + "import java.util.Map;\n\n"
                + "public final class PageDashboardSocketController implements SocketController {\n"
                + "    public String appId() { return \"page.dashboard\"; }\n"
                + "    public Map<String, SocketEventHandler> handlers() { return Map.of(); }\n"
                + "}\n";
    }

    private String portalSocketJava() {
        return "import com.wiz.socket.SocketController;\n"
                + "import com.wiz.socket.SocketEventHandler;\n"
                + "import java.util.Map;\n\n"
                + "public final class PortalPostListSocketController implements SocketController {\n"
                + "    public String appId() { return \"portal.post.list\"; }\n"
                + "    public Map<String, SocketEventHandler> handlers() { return Map.of(); }\n"
                + "}\n";
    }

    private String echoRouteJava() {
        return "import com.wiz.dispatch.RouteHandler;\n"
                + "import com.wiz.runtime.WizContext;\n"
                + "import com.wiz.runtime.WizResult;\n"
                + "import com.wiz.runtime.WizSegment;\n"
                + "import java.util.Map;\n\n"
                + "public final class CustomEchoRouteHandler implements RouteHandler {\n"
                + "    public String routeId() { return \"custom.echo\"; }\n"
                + "    public WizResult handle(WizContext context, WizSegment segment) {\n"
                + "        return context.response().ok(Map.of(\"name\", segment.require(\"name\")));\n"
                + "    }\n"
                + "}\n";
    }
}
