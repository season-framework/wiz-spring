package com.wiz.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void removesStaleResolvedDependenciesWhenWorkspacePomIsRemoved() throws Exception {
        Path workspace = tempDir.resolve("dependency-cleanup-workspace");
        Files.createDirectories(workspace.resolve("src"));
        Files.createDirectories(workspace.resolve("config"));
        ProjectContext project = new PathService(workspace).workspaceContext();
        Path stale = ProjectBuildLayout.dependencyRoot(project).resolve("removed-1.0.jar");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "stale");

        BuildResult result = new ProjectBuildService().build(project, false, "compile");

        assertTrue(result.success(), result.message());
        assertTrue(Files.isRegularFile(workspace.resolve(".wiz/build.lock")));
        assertTrue(Files.notExists(ProjectBuildLayout.dependencyRoot(project)));
        assertTrue(Files.notExists(ProjectBuildLayout.dependencyStagingRoot(project)));
    }

    @Test
    void reconstructsSourceTreeAndFlattensPortalApps() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        Files.createDirectories(project.sourceRoot().resolve("portal/post"));
        Files.writeString(project.sourceRoot().resolve("portal/post/portal.json"), "{\"use_app\":true,\"use_route\":true}\n");
        Files.createDirectories(project.sourceRoot().resolve("portal/post/app/list"));
        Files.writeString(project.sourceRoot().resolve("portal/post/app/list/app.json"), "{\"controller\":\"guard\"}\n");
        Files.createDirectories(project.sourceRoot().resolve("portal/post/route/auth"));
        Files.writeString(project.sourceRoot().resolve("portal/post/route/auth/app.json"), "{\"controller\":\"guard\"}\n");

        BuildResult result = new ProjectBuildService().build(project, true, "reconstruct");

        assertTrue(result.success(), result.message());
        assertEquals(java.util.List.of("reconstruct"), result.phases());
        assertTrue(Files.exists(ProjectBuildLayout.stagedAppRoot(project).resolve("page.dashboard/api.java")));
        assertTrue(Files.exists(ProjectBuildLayout.stagedAppRoot(project).resolve("portal.post.list/app.json")));
        assertTrue(Files.exists(ProjectBuildLayout.stagedRouteRoot(project).resolve("portal.post.auth/app.json")));
        String appJson = Files.readString(ProjectBuildLayout.stagedAppRoot(project).resolve("portal.post.list/app.json"));
        assertTrue(appJson.contains("\"id\" : \"portal.post.list\""));
        assertTrue(appJson.contains("\"mode\" : \"portal\""));
        assertTrue(appJson.contains("\"controller\" : \"portal/post/guard\""));
    }

    @Test
    void portalFlagsControlFlattenedBuildInputs() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
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
        assertTrue(Files.notExists(ProjectBuildLayout.stagedAppRoot(project).resolve("portal.post.list/app.json")));
        assertTrue(Files.exists(ProjectBuildLayout.stagedRouteRoot(project).resolve("portal.post.auth/app.json")));
        assertTrue(Files.exists(ProjectBuildLayout.stagedControllerRoot(project).resolve("portal/post/GuardController.java")));
        assertTrue(Files.exists(ProjectBuildLayout.stagedModelRoot(project).resolve("portal/post/PostStruct.java")));
        assertTrue(Files.notExists(ProjectBuildLayout.stagedAssetsRoot(project).resolve("portal/post/logo.txt")));
    }

    @Test
    void normalizesAppAndRouteMetadataDefaults() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        Files.createDirectories(project.appRoot().resolve("custom.echo"));
        Files.writeString(project.appRoot().resolve("custom.echo/app.json"), "{\"controller\":\"\",\"viewuri\":\"/echo\"}\n");
        Files.writeString(project.appRoot().resolve("custom.echo/api.java"), "public final class CustomEchoApi {}\n");
        Files.writeString(project.appRoot().resolve("custom.echo/socket.java"), "public final class CustomEchoSocketController {}\n");
        Files.createDirectories(project.routeRoot().resolve("custom.api"));
        Files.writeString(project.routeRoot().resolve("custom.api/app.json"), "{}\n");

        BuildResult result = new ProjectBuildService().build(project, true, "reconstruct");

        assertTrue(result.success());
        String appJson = Files.readString(ProjectBuildLayout.stagedAppRoot(project).resolve("custom.echo/app.json"));
        assertTrue(appJson.contains("\"id\" : \"custom.echo\""));
        assertTrue(appJson.contains("\"mode\" : \"app\""));
        assertTrue(appJson.contains("\"controller\" : \"base\""));
        assertTrue(appJson.contains("\"path\" : \"./custom.echo/custom.echo.component\""));
        assertTrue(appJson.contains("\"template\" : \"wiz-custom-echo()\""));
        assertTrue(appJson.contains("\"handler\" : \"com.wiz.app.web.api.CustomEchoApi\""));
        assertTrue(appJson.contains("\"handler\" : \"com.wiz.app.realtime.socket.CustomEchoSocketController\""));

        String routeJson = Files.readString(ProjectBuildLayout.stagedRouteRoot(project).resolve("custom.api/app.json"));
        assertTrue(routeJson.contains("\"id\" : \"custom.api\""));
        assertTrue(routeJson.contains("\"route\" : \"/custom/api\""));
        assertTrue(routeJson.contains("\"path\" : \"/custom/api\""));
        assertTrue(routeJson.contains("\"controller\" : \"base\""));
        assertTrue(routeJson.contains("\"handler\" : \"com.wiz.app.web.route.CustomApiRouteHandler\""));
    }

    @Test
    void rejectsUnsupportedBuildPhase() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);

        BuildResult result = new ProjectBuildService().build(project, false, "full");

        assertEquals(2, result.exitCode());
    }

    @Test
    void compilesAppLocalJavaApiAndCreatesBundle() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        removeAngularSource(project);

        BuildResult result = new ProjectBuildService().build(project, true, "bundle");

        assertTrue(result.success());
        assertEquals(java.util.List.of("reconstruct", "java-source", "app-dependencies", "java-compile", "frontend-fallback", "bundle"), result.phases());
        assertTrue(Files.exists(ProjectBuildLayout.generatedJavaSourceRoot(project).resolve("com/wiz/app/web/api/PageDashboardApi.java")));
        assertTrue(Files.exists(ProjectBuildLayout.generatedResourcesRoot(project).resolve("application.yml")));
        assertTrue(Files.exists(ProjectBuildLayout.generatedPom(project)));
        assertTrue(Files.exists(ProjectBuildLayout.classesRoot(project).resolve("com/wiz/app/web/api/PageDashboardApi.class")));
        assertTrue(Files.exists(ProjectBuildLayout.appApiJar(project)));
        assertTrue(Files.notExists(project.buildRoot().resolve("main/java")));
        assertTrue(Files.notExists(project.buildRoot().resolve("classes")));
        assertTrue(Files.notExists(project.buildRoot().resolve("app-api.jar")));
        assertTrue(Files.notExists(project.buildRoot().resolve("src/app")));
        assertTrue(Files.exists(ProjectBuildLayout.stagedAppRoot(project).resolve("page.dashboard/api.java")));
        assertTrue(Files.exists(project.bundleRoot().resolve("app-api.jar")));
        assertTrue(Files.exists(project.bundleRoot().resolve("classes/com/wiz/app/web/api/PageDashboardApi.class")));
        assertTrue(Files.exists(project.bundleRoot().resolve("src/app/page.dashboard/api.java")));
        assertTrue(Files.exists(project.bundleWwwRoot().resolve("index.html")));
        assertTrue(Files.exists(project.bundleWwwRoot().resolve("app.js")));
        assertFalse(Files.readString(project.bundleWwwRoot().resolve("index.html")).contains("config.js"));
        assertTrue(Files.exists(project.bundleRoot().resolve(SupplyChainManifestService.DEPENDENCY_MANIFEST_FILE)));
        assertTrue(Files.exists(ProjectBuildLayout.cyclonedxBom(project)));
        String marker = Files.readString(project.bundleRoot().resolve(BuildMarkerService.MARKER_FILE));
        assertTrue(marker.contains("\"frontendMode\" : \"fallback\""));
        assertTrue(marker.contains("\"buildPhases\""));
        assertTrue(marker.contains("\"dependencyDigest\""));
        assertTrue(marker.contains("\"dependencyManifest\" : \"bundle/.wiz-dependencies.json\""));
        String dependencies = Files.readString(project.bundleRoot().resolve(SupplyChainManifestService.DEPENDENCY_MANIFEST_FILE));
        assertTrue(dependencies.contains("\"dependencyDigest\""));
        assertTrue(dependencies.contains("\"dependencies\""));
        String bom = Files.readString(ProjectBuildLayout.cyclonedxBom(project));
        assertTrue(bom.contains("\"bomFormat\" : \"CycloneDX\""));
    }

    @Test
    void normalBuildRecreatesGeneratedApiFromHandlerNamedAppJavaFile() throws Exception {
        Path workspace = tempDir.resolve("handler-named-workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        removeAngularSource(project);

        BuildResult initial = new ProjectBuildService().build(project, true, "bundle");
        assertTrue(initial.success(), initial.message());

        Files.delete(project.appRoot().resolve("page.access/api.java"));
        Files.writeString(project.appRoot().resolve("page.access/PageAccessApi.java"), handlerNamedAccessApi());
        BuildResult rebuild = new ProjectBuildService().build(project, false, "bundle");

        assertTrue(rebuild.success(), rebuild.message());
        Path generated = ProjectBuildLayout.generatedJavaSourceRoot(project).resolve("com/wiz/app/web/api/PageAccessApi.java");
        String generatedSource = Files.readString(generated);
        assertTrue(generatedSource.contains("handler-named-api"));
        assertFalse(generatedSource.contains("authenticate"));
        assertTrue(Files.exists(project.bundleRoot().resolve("classes/com/wiz/app/web/api/PageAccessApi.class")));
        String appJson = Files.readString(project.bundleRoot().resolve("src/app/page.access/app.json"));
        assertTrue(appJson.contains("\"handler\" : \"com.wiz.app.web.api.PageAccessApi\""));
    }

    @Test
    void normalReconstructPreservesFrontendDependenciesAndRemovesStaleInputs() throws Exception {
        Path workspace = tempDir.resolve("normal-reconstruct-workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        Path nodeModuleBinary = ProjectBuildLayout.stagedAngularRoot(project).resolve("node_modules/.bin/ng");
        Path staleApp = ProjectBuildLayout.stagedAppRoot(project).resolve("stale/app.json");
        Files.createDirectories(nodeModuleBinary.getParent());
        Files.createDirectories(staleApp.getParent());
        Files.writeString(nodeModuleBinary, "#!/usr/bin/env node\n");
        Files.writeString(staleApp, "{}\n");

        BuildResult result = new ProjectBuildService().build(project, false, "reconstruct");

        assertTrue(result.success(), result.message());
        assertTrue(Files.exists(nodeModuleBinary));
        assertTrue(Files.notExists(staleApp));
        assertTrue(Files.exists(ProjectBuildLayout.stagedAppRoot(project).resolve("page.access/app.json")));
    }

    @Test
    void fallbackDefaultApiScriptUsesBuildTimeApiPrefixConfig() throws Exception {
        Path workspace = tempDir.resolve("fallback-workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        removeAngularSource(project);
        removeViewScripts(project);

        BuildResult result = new ProjectBuildService().build(project, true, "bundle");

        assertTrue(result.success());
        String index = Files.readString(project.bundleWwwRoot().resolve("index.html"));
        String script = Files.readString(project.bundleWwwRoot().resolve("app.js"));
        assertFalse(index.contains("config.js"));
        assertTrue(script.contains("const apiPrefix = \"/wiz/api\";"));
        assertFalse(script.contains("__WIZ_CONFIG__"));
        assertTrue(script.contains("`${apiPrefix}/page.dashboard/overview`"));
    }

    @Test
    void packagesStandaloneProjectJarWithEmbeddedWorkspaceBundle() throws Exception {
        Path workspace = tempDir.resolve("standalone-workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        removeAngularSource(project);
        BuildResult result = new ProjectBuildService().build(project, true, "bundle");
        assertTrue(result.success(), result.message());

        Path runtimeJar = tempDir.resolve("runtime.jar");
        writeFakeRuntimeJar(runtimeJar);
        Path output = tempDir.resolve("main.jar");

        Path jar = new StandaloneProjectJarService().packageJar(workspace, project, runtimeJar, output);

        assertTrue(Files.exists(jar));
        Path checksum = jar.resolveSibling(jar.getFileName() + ".sha256");
        assertTrue(Files.exists(checksum));
        assertTrue(Files.readString(checksum).matches("[a-f0-9]{64}  main\\.jar\\R"));
        try (java.util.jar.JarFile packaged = new java.util.jar.JarFile(jar.toFile())) {
            assertTrue(packaged.getEntry("BOOT-INF/classes/wiz/embedded-workspace.properties") != null);
            assertTrue(packaged.getEntry("BOOT-INF/classes/wiz/embedded-workspace.files") != null);
            assertTrue(packaged.getEntry("BOOT-INF/classes/wiz/embedded-workspace/config/application.yml") != null);
            assertTrue(packaged.getEntry("BOOT-INF/classes/wiz/embedded-workspace/config/application.yml") != null);
            assertTrue(packaged.getEntry("BOOT-INF/classes/wiz/embedded-workspace/bundle/classes/com/wiz/app/web/api/PageDashboardApi.class") != null);
            String workspaceConfig = jarEntry(packaged, "BOOT-INF/classes/wiz/embedded-workspace/config/application.yml");
            String projectConfig = jarEntry(packaged, "BOOT-INF/classes/wiz/embedded-workspace/config/application.yml");
            assertTrue(workspaceConfig.contains("server:"));
            assertTrue(workspaceConfig.contains("port:"));
            assertTrue(projectConfig.contains("package-root: com.wiz.app"));
        }
    }

    @Test
    void compilesProjectControllerJavaSources() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        removeAngularSource(project);
        Files.writeString(project.sourceRoot().resolve("controller/GuardController.java"), guardControllerJava());

        BuildResult result = new ProjectBuildService().build(project, true, "bundle");

        assertTrue(result.success());
        assertTrue(Files.exists(ProjectBuildLayout.generatedJavaSourceRoot(project).resolve("com/wiz/app/security/guard/GuardController.java")));
        assertTrue(Files.exists(ProjectBuildLayout.classesRoot(project).resolve("com/wiz/app/security/guard/GuardController.class")));
        assertTrue(Files.exists(project.bundleRoot().resolve("classes/com/wiz/app/security/guard/GuardController.class")));
    }

    @Test
    void compilesAppLocalSocketJavaSources() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        removeAngularSource(project);
        Files.writeString(project.appRoot().resolve("page.dashboard/socket.java"), dashboardSocketJava());
        Files.createDirectories(project.sourceRoot().resolve("portal/post"));
        Files.writeString(project.sourceRoot().resolve("portal/post/portal.json"), "{\"use_app\":true,\"use_model\":true}\n");
        Files.createDirectories(project.sourceRoot().resolve("portal/post/app/list"));
        Files.writeString(project.sourceRoot().resolve("portal/post/app/list/app.json"), "{}\n");
        Files.writeString(project.sourceRoot().resolve("portal/post/app/list/socket.java"), portalSocketJava());

        BuildResult result = new ProjectBuildService().build(project, true, "bundle");

        assertTrue(result.success(), result.message());
        assertTrue(Files.exists(ProjectBuildLayout.generatedJavaSourceRoot(project).resolve("com/wiz/app/realtime/socket/PageDashboardSocketController.java")));
        assertTrue(Files.exists(ProjectBuildLayout.classesRoot(project).resolve("com/wiz/app/realtime/socket/PageDashboardSocketController.class")));
        assertTrue(Files.exists(ProjectBuildLayout.classesRoot(project).resolve("com/wiz/app/realtime/socket/PortalPostListSocketController.class")));
        assertTrue(Files.exists(project.bundleRoot().resolve("classes/com/wiz/app/realtime/socket/PageDashboardSocketController.class")));
    }

    @Test
    void compilesRouteLocalJavaSources() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        removeAngularSource(project);
        Files.createDirectories(project.routeRoot().resolve("custom.echo"));
        Files.writeString(project.routeRoot().resolve("custom.echo/app.json"), "{\"id\":\"custom.echo\",\"route\":\"/echo/<name>\"}\n");
        Files.writeString(project.routeRoot().resolve("custom.echo/route.java"), echoRouteJava());

        BuildResult result = new ProjectBuildService().build(project, true, "bundle");

        assertTrue(result.success());
        assertTrue(Files.exists(ProjectBuildLayout.generatedJavaSourceRoot(project).resolve("com/wiz/app/web/route/CustomEchoRouteHandler.java")));
        assertTrue(Files.exists(ProjectBuildLayout.classesRoot(project).resolve("com/wiz/app/web/route/CustomEchoRouteHandler.class")));
        assertTrue(Files.exists(project.bundleRoot().resolve("classes/com/wiz/app/web/route/CustomEchoRouteHandler.class")));
    }

    @Test
    void compilesProjectModelAndPortalModelJavaSources() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
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
        assertTrue(Files.exists(ProjectBuildLayout.classesRoot(project).resolve("com/wiz/app/application/model/Struct.class")));
        assertTrue(Files.exists(ProjectBuildLayout.classesRoot(project).resolve("com/wiz/app/application/service/UserStruct.class")));
        assertTrue(Files.exists(ProjectBuildLayout.classesRoot(project).resolve("com/wiz/app/module/post/application/model/PostStruct.class")));
        assertTrue(Files.exists(project.bundleRoot().resolve("classes/com/wiz/app/module/post/application/service/PostService.class")));
    }

    private void writeFakeRuntimeJar(Path jar) throws Exception {
        try (java.util.jar.JarOutputStream output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new java.util.jar.JarEntry("META-INF/MANIFEST.MF"));
            output.write("Manifest-Version: 1.0\nMain-Class: com.wiz.WizSpringApplication\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private String jarEntry(java.util.jar.JarFile jar, String name) throws Exception {
        try (var input = jar.getInputStream(jar.getEntry(name))) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
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

    private void removeViewScripts(ProjectContext project) throws Exception {
        try (var paths = Files.walk(project.sourceRoot())) {
            for (Path path : paths.filter(item -> Files.isRegularFile(item) && item.getFileName().toString().equals("view.ts")).toList()) {
                Files.delete(path);
            }
        }
    }

    private String handlerNamedAccessApi() {
        return "public final class PageAccessApi {\n"
                + "    public Object login() {\n"
                + "        return java.util.Map.of(\"message\", \"handler-named-api\");\n"
                + "    }\n"
                + "}\n";
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
