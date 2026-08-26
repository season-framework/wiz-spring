package com.wiz.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.runtime.BuildMarkerService;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.WorkspaceRuntimePaths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProjectBuildServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void removesStaleResolvedDependenciesWhenWorkspacePomIsRemoved() throws Exception {
        Path workspace = tempDir.resolve("dependency-cleanup-workspace");
        Files.createDirectories(workspace.resolve("src/app"));
        Files.createDirectories(workspace.resolve("config"));
        ProjectContext project = new PathService(workspace).workspaceContext();
        Path stale = ProjectBuildLayout.dependencyRoot(project).resolve("removed-1.0.jar");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "stale");

        BuildResult result = new ProjectBuildService().build(project, false, "compile");

        assertTrue(result.success(), result.message());
        assertTrue(Files.isRegularFile(WorkspaceRuntimePaths.buildLock(workspace)));
        assertNoWizDirectories(workspace);
        assertTrue(Files.notExists(ProjectBuildLayout.dependencyRoot(project)));
        assertTrue(Files.notExists(ProjectBuildLayout.dependencyStagingRoot(project)));
    }

    @Test
    void keepsMavenCacheMetadataOutOfTheRuntimeBundle() throws Exception {
        ProjectContext project = emptyProject("maven-cache-bundle-workspace");
        Files.writeString(project.root().resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>cache-bundle-test</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        Path wrapper = project.root().resolve("mvnw");
        Files.writeString(wrapper, "#!/bin/sh\nexit 0\n");
        wrapper.toFile().setExecutable(true, false);

        BuildResult result = new ProjectBuildService().build(project, false, "bundle");

        assertTrue(result.success(), result.message());
        assertTrue(Files.isRegularFile(ProjectBuildLayout.dependencyRoot(project)
                .resolve(MavenDependencyCache.STATE_FILE)));
        assertTrue(Files.notExists(project.bundleRoot().resolve("lib")
                .resolve(MavenDependencyCache.STATE_FILE)));
    }

    @Test
    void rejectsBundleOnlyWorkspaceBeforeChangingBuildOutputs() throws Exception {
        Path workspace = tempDir.resolve("bundle-only-workspace");
        Path buildSentinel = workspace.resolve("build/keep.txt");
        Path bundleSentinel = workspace.resolve("bundle/keep.txt");
        Path marker = workspace.resolve("bundle").resolve(BuildMarkerService.MARKER_FILE);
        Files.createDirectories(workspace.resolve("bundle/src/app"));
        Files.createDirectories(buildSentinel.getParent());
        Files.writeString(buildSentinel, "build-output\n");
        Files.writeString(bundleSentinel, "last-good-bundle\n");
        Files.writeString(marker, "last-good-marker\n");
        ProjectContext project = new PathService(workspace).workspaceContext();

        BuildResult result = new ProjectBuildService().build(project, true, "bundle");

        assertEquals(2, result.exitCode());
        assertTrue(result.message().contains("src/app"));
        assertEquals("build-output\n", Files.readString(buildSentinel));
        assertEquals("last-good-bundle\n", Files.readString(bundleSentinel));
        assertEquals("last-good-marker\n", Files.readString(marker));
        assertTrue(Files.notExists(ProjectBuildLayout.bundleStagingRoot(project)));
    }

    @Test
    void removesStaleJavaOutputsWhenRebuildHasNoJavaSources() throws Exception {
        ProjectContext project = emptyProject("no-java-source-workspace");
        Path staleBuildClass = ProjectBuildLayout.classesRoot(project).resolve("old/Stale.class");
        Path staleBundleClass = project.bundleRoot().resolve("classes/old/Stale.class");
        Files.createDirectories(staleBuildClass.getParent());
        Files.createDirectories(staleBundleClass.getParent());
        Files.writeString(staleBuildClass, "stale-class\n");
        Files.writeString(ProjectBuildLayout.appApiJar(project), "stale-jar\n");
        Files.writeString(staleBundleClass, "stale-class\n");
        Files.writeString(project.bundleRoot().resolve("app-api.jar"), "stale-jar\n");

        BuildResult result = new ProjectBuildService().build(project, false, "bundle");

        assertTrue(result.success(), result.message());
        assertTrue(Files.notExists(ProjectBuildLayout.classesRoot(project)));
        assertTrue(Files.notExists(ProjectBuildLayout.appApiJar(project)));
        assertTrue(Files.notExists(project.bundleRoot().resolve("classes")));
        assertTrue(Files.notExists(project.bundleRoot().resolve("app-api.jar")));
        assertTrue(Files.isRegularFile(project.bundleRoot().resolve(BuildMarkerService.MARKER_FILE)));
    }

    @Test
    void compileDoesNotUseDependenciesFromThePreviouslyPublishedBundle() throws Exception {
        ProjectContext project = emptyProject("previous-bundle-classpath-workspace");
        Path app = project.appRoot().resolve("page.dashboard");
        Files.createDirectories(app);
        Files.writeString(app.resolve("api.java"), """
                import legacy.OnlyInPreviousBundle;

                public final class PageDashboardApi {
                    public Object value() { return OnlyInPreviousBundle.value(); }
                }
                """);
        Path previousBundleJar = writeDependencyJar(project.bundleRoot().resolve("lib/legacy-only.jar"));

        BuildResult bundleOnly = new ProjectBuildService().build(project, false, "compile");

        assertFalse(bundleOnly.success());
        assertTrue(bundleOnly.message().contains("OnlyInPreviousBundle"), bundleOnly.message());

        Path workspaceJar = project.root().resolve("lib/legacy-only.jar");
        Files.createDirectories(workspaceJar.getParent());
        Files.copy(previousBundleJar, workspaceJar);

        BuildResult declaredWorkspaceLibrary = new ProjectBuildService().build(project, false, "compile");

        assertTrue(declaredWorkspaceLibrary.success(), declaredWorkspaceLibrary.message());
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
        assertTrue(marker.contains("\"cycloneDxBom\" : \"bundle/bom.json\""));
        String dependencies = Files.readString(project.bundleRoot().resolve(SupplyChainManifestService.DEPENDENCY_MANIFEST_FILE));
        assertTrue(dependencies.contains("\"dependencyDigest\""));
        assertTrue(dependencies.contains("\"dependencies\""));
        assertTrue(dependencies.contains("\"cycloneDxBom\" : \"bom.json\""));
        String bom = Files.readString(ProjectBuildLayout.cyclonedxBom(project));
        assertTrue(bom.contains("\"bomFormat\" : \"CycloneDX\""));
        assertNoWizDirectories(workspace);
    }

    @Test
    void replacesExplicitPackageDeclarationAfterLeadingComments() throws Exception {
        ProjectContext project = emptyProject("explicit-package-workspace");
        Path app = project.appRoot().resolve("page.explicit");
        Files.createDirectories(app);
        Files.writeString(app.resolve("app.json"), "{}\n");
        Files.writeString(app.resolve("api.java"), """
                /* Existing repositories commonly include a license before package. */
                package legacy.mismatched.api;

                public final class PageExplicitApi {}
                """);

        BuildResult result = new ProjectBuildService().build(project, true, "compile");

        assertTrue(result.success(), result.message());
        Path generated = ProjectBuildLayout.generatedJavaSourceRoot(project)
                .resolve("com/wiz/app/web/api/PageExplicitApi.java");
        String generatedSource = Files.readString(generated);
        assertTrue(generatedSource.contains("package com.wiz.app.web.api;"));
        assertFalse(generatedSource.contains("package legacy.mismatched.api;"));
        assertEquals(1, generatedSource.lines().filter(line -> line.startsWith("package ")).count());
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
        Path angularRoot = ProjectBuildLayout.stagedAngularRoot(project);
        Path nodeModuleBinary = angularRoot.resolve("node_modules/.bin/ng");
        Path angularCache = angularRoot.resolve(".angular/cache/state.bin");
        Path staleAngularState = angularRoot.resolve(".angular/stale/state.bin");
        Path staleAngularOutput = angularRoot.resolve("dist/stale.js");
        Path staleApp = ProjectBuildLayout.stagedAppRoot(project).resolve("stale/app.json");
        Files.createDirectories(nodeModuleBinary.getParent());
        Files.createDirectories(angularCache.getParent());
        Files.createDirectories(staleAngularState.getParent());
        Files.createDirectories(staleAngularOutput.getParent());
        Files.createDirectories(staleApp.getParent());
        Files.writeString(nodeModuleBinary, "#!/usr/bin/env node\n");
        Files.writeString(angularCache, "cached-angular-state\n");
        Files.writeString(staleAngularState, "stale-angular-state\n");
        Files.writeString(staleAngularOutput, "stale-angular-output\n");
        Files.writeString(staleApp, "{}\n");

        BuildResult result = new ProjectBuildService().build(project, false, "reconstruct");

        assertTrue(result.success(), result.message());
        assertTrue(Files.exists(nodeModuleBinary));
        assertEquals("cached-angular-state\n", Files.readString(angularCache));
        assertTrue(Files.notExists(staleAngularState));
        assertTrue(Files.notExists(staleAngularOutput));
        assertTrue(Files.notExists(staleApp));
        assertTrue(Files.exists(ProjectBuildLayout.stagedAppRoot(project).resolve("page.access/app.json")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"reconstruct", "compile"})
    void cleanNonPublishingPhasesPreservePublishedBundleAndItsBom(String phase) throws Exception {
        ProjectContext project = emptyProject("clean-" + phase + "-preserves-bundle-workspace");
        Path sentinel = project.bundleRoot().resolve("last-good.txt");
        Path bom = project.bundleRoot().resolve(SupplyChainManifestService.CYCLONEDX_BOM_FILE);
        Files.createDirectories(project.bundleRoot());
        Files.writeString(sentinel, "last-good-bundle\n");
        Files.writeString(bom, "last-good-bom\n");

        BuildResult result = new ProjectBuildService().build(project, true, phase);

        assertTrue(result.success(), result.message());
        assertEquals("last-good-bundle\n", Files.readString(sentinel));
        assertEquals("last-good-bom\n", Files.readString(bom));
    }

    @Test
    void fallbackBundleDoesNotReuseStaleFrontendOutput() throws Exception {
        ProjectContext project = emptyProject("stale-frontend-workspace");
        Path app = project.appRoot().resolve("page.dashboard");
        Files.createDirectories(app);
        Files.writeString(app.resolve("view.html"), "<main>fresh-fallback</main>\n");
        Path frontendOutput = ProjectBuildLayout.frontendOutputRoot(project);
        Files.createDirectories(frontendOutput);
        Files.writeString(frontendOutput.resolve("index.html"), "<main>stale-angular-output</main>\n");
        Files.writeString(frontendOutput.resolve("legacy.js"), "console.log('stale');\n");

        BuildResult result = new ProjectBuildService().build(project, false, "bundle");

        assertTrue(result.success(), result.message());
        String index = Files.readString(project.bundleWwwRoot().resolve("index.html"));
        assertTrue(index.contains("fresh-fallback"));
        assertFalse(index.contains("stale-angular-output"));
        assertTrue(Files.notExists(project.bundleWwwRoot().resolve("legacy.js")));
        assertTrue(Files.notExists(frontendOutput));
    }

    @Test
    void cleanBundleCompileFailurePreservesPreviouslyPublishedBundle() throws Exception {
        ProjectContext project = emptyProject("failed-clean-build-workspace");
        Path app = project.appRoot().resolve("page.dashboard");
        Files.createDirectories(app);
        Files.writeString(app.resolve("api.java"), "public final class PageDashboardApi { this is not Java; }\n");
        Path marker = project.bundleRoot().resolve(BuildMarkerService.MARKER_FILE);
        Path sentinel = project.bundleRoot().resolve("last-good.txt");
        Path bom = project.bundleRoot().resolve(SupplyChainManifestService.CYCLONEDX_BOM_FILE);
        Files.createDirectories(project.bundleRoot());
        Files.writeString(marker, "last-good-marker\n");
        Files.writeString(sentinel, "last-good-bundle\n");
        Files.writeString(bom, "last-good-bom\n");

        BuildResult result = new ProjectBuildService().build(project, true, "bundle");

        assertFalse(result.success());
        assertEquals("last-good-marker\n", Files.readString(marker));
        assertEquals("last-good-bundle\n", Files.readString(sentinel));
        assertEquals("last-good-bom\n", Files.readString(bom));
        assertTrue(Files.notExists(ProjectBuildLayout.bundleStagingRoot(project)));
        assertTrue(Files.notExists(ProjectBuildLayout.bundlePreviousRoot(project)));
    }

    @Test
    void bundleAssemblyFailurePreservesPublishedBundleAndCleansStaging() throws Exception {
        ProjectContext project = emptyProject("failed-bundle-assembly-workspace");
        Path marker = project.bundleRoot().resolve(BuildMarkerService.MARKER_FILE);
        Path sentinel = project.bundleRoot().resolve("last-good.txt");
        Path bom = project.bundleRoot().resolve(SupplyChainManifestService.CYCLONEDX_BOM_FILE);
        Files.createDirectories(project.bundleRoot());
        Files.writeString(marker, "last-good-marker\n");
        Files.writeString(sentinel, "last-good-bundle\n");
        Files.writeString(bom, "last-good-bom\n");
        Path outside = tempDir.resolve("outside-library.jar");
        Files.writeString(outside, "not-a-library\n");
        Path linkedLibrary = project.root().resolve("lib/linked.jar");
        Files.createDirectories(linkedLibrary.getParent());
        createSymbolicLinkOrSkip(linkedLibrary, outside);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ProjectBuildService().build(project, true, "bundle"));

        assertTrue(failure.getMessage().contains("Symbolic links are not allowed"));
        assertEquals("last-good-marker\n", Files.readString(marker));
        assertEquals("last-good-bundle\n", Files.readString(sentinel));
        assertEquals("last-good-bom\n", Files.readString(bom));
        assertTrue(Files.notExists(ProjectBuildLayout.bundleStagingRoot(project)));
        assertTrue(Files.notExists(ProjectBuildLayout.bundlePreviousRoot(project)));
    }

    @Test
    void reconstructRestoresPreviousBundleWhenPublishWasInterruptedWithoutLiveBundle() throws Exception {
        ProjectContext project = emptyProject("restore-interrupted-bundle-workspace");
        Path previous = ProjectBuildLayout.bundlePreviousRoot(project);
        Files.createDirectories(previous);
        Files.writeString(previous.resolve("last-good.txt"), "restored-bundle\n");
        Files.writeString(previous.resolve(SupplyChainManifestService.CYCLONEDX_BOM_FILE), "restored-bom\n");

        BuildResult result = new ProjectBuildService().build(project, false, "reconstruct");

        assertTrue(result.success(), result.message());
        assertEquals("restored-bundle\n", Files.readString(project.bundleRoot().resolve("last-good.txt")));
        assertEquals("restored-bom\n",
                Files.readString(project.bundleRoot().resolve(SupplyChainManifestService.CYCLONEDX_BOM_FILE)));
        assertTrue(Files.notExists(previous));
    }

    @Test
    void reconstructKeepsLiveBundleAndCleansPreviousBundleAfterInterruptedPublish() throws Exception {
        ProjectContext project = emptyProject("clean-interrupted-bundle-workspace");
        Path liveSentinel = project.bundleRoot().resolve("last-good.txt");
        Path previous = ProjectBuildLayout.bundlePreviousRoot(project);
        Files.createDirectories(project.bundleRoot());
        Files.createDirectories(previous);
        Files.writeString(liveSentinel, "live-bundle\n");
        Files.writeString(previous.resolve("last-good.txt"), "superseded-bundle\n");

        BuildResult result = new ProjectBuildService().build(project, false, "reconstruct");

        assertTrue(result.success(), result.message());
        assertEquals("live-bundle\n", Files.readString(liveSentinel));
        assertTrue(Files.notExists(previous));
    }

    @Test
    void rejectsSymbolicBuildRootWithoutTouchingExternalDirectory() throws Exception {
        Path workspace = tempDir.resolve("symbolic-build-root-workspace");
        Files.createDirectories(workspace.resolve("src/app"));
        Files.createDirectories(workspace.resolve("config"));
        Path outside = tempDir.resolve("external-build-root");
        Path sentinel = outside.resolve("sentinel.txt");
        Files.createDirectories(outside);
        Files.writeString(sentinel, "external-data\n");
        createSymbolicLinkOrSkip(workspace.resolve("build"), outside);
        ProjectContext project = new PathService(workspace).workspaceContext();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ProjectBuildService().build(project, false, "reconstruct"));

        assertTrue(failure.getMessage().contains("build"), failure.getMessage());
        assertEquals("external-data\n", Files.readString(sentinel));
    }

    @Test
    void rejectsSymbolicBuildTargetWithoutTouchingExternalDirectory() throws Exception {
        Path workspace = tempDir.resolve("symbolic-build-target-workspace");
        Files.createDirectories(workspace.resolve("src/app"));
        Files.createDirectories(workspace.resolve("config"));
        Files.createDirectories(workspace.resolve("build"));
        Path outside = tempDir.resolve("external-build-target");
        Path sentinel = outside.resolve("sentinel.txt");
        Files.createDirectories(outside);
        Files.writeString(sentinel, "external-data\n");
        createSymbolicLinkOrSkip(workspace.resolve("build/target"), outside);
        ProjectContext project = new PathService(workspace).workspaceContext();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ProjectBuildService().build(project, false, "reconstruct"));

        assertTrue(failure.getMessage().contains("target"), failure.getMessage());
        assertEquals("external-data\n", Files.readString(sentinel));
    }

    @Test
    void serializesConcurrentPackageChangesWithTheirBuilds() throws Exception {
        Path workspace = tempDir.resolve("concurrent-package-build-workspace");
        Path api = workspace.resolve("src/app/page.dashboard/api.java");
        Files.createDirectories(api.getParent());
        Files.createDirectories(workspace.resolve("config"));
        Files.writeString(api, "// com.wiz.app\npublic final class PageDashboardApi {}\n");
        Files.writeString(workspace.resolve("config/application.yml"), """
                wiz:
                  java:
                    package-root: com.wiz.app
                """);

        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                start.await();
                return new ProjectBuildService().build(
                        new PathService(workspace), "com.example.first", false, "bundle", BuildLogger.quiet());
            });
            var second = executor.submit(() -> {
                start.await();
                return new ProjectBuildService().build(
                        new PathService(workspace), "com.example.second", false, "bundle", BuildLogger.quiet());
            });
            start.countDown();

            assertTrue(first.get(30, TimeUnit.SECONDS).result().success());
            assertTrue(second.get(30, TimeUnit.SECONDS).result().success());
        } finally {
            executor.shutdownNow();
        }

        String selectedPackage = new PathService(workspace).packageRoot();
        assertTrue(Set.of("com.example.first", "com.example.second").contains(selectedPackage));
        assertTrue(Files.readString(api).contains("// " + selectedPackage));
        String marker = Files.readString(workspace.resolve("bundle").resolve(BuildMarkerService.MARKER_FILE));
        assertTrue(marker.contains("\"javaPackageRoot\" : \"" + selectedPackage + "\""), marker);
        assertTrue(Files.readString(workspace.resolve("bundle/src/app/page.dashboard/api.java"))
                .contains("// " + selectedPackage));
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

    private ProjectContext emptyProject(String name) throws IOException {
        Path workspace = tempDir.resolve(name);
        Files.createDirectories(workspace.resolve("src/app"));
        Files.createDirectories(workspace.resolve("config"));
        return new PathService(workspace).workspaceContext();
    }

    private Path writeDependencyJar(Path jar) throws Exception {
        Path fixture = tempDir.resolve("dependency-fixture-" + java.util.UUID.randomUUID());
        Path source = fixture.resolve("src/legacy/OnlyInPreviousBundle.java");
        Path classes = fixture.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, """
                package legacy;

                public final class OnlyInPreviousBundle {
                    private OnlyInPreviousBundle() {}
                    public static String value() { return "legacy"; }
                }
                """);
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "A JDK compiler is required for the build classpath regression fixture");
        assertEquals(0, compiler.run(null, null, null,
                "--release", "21", "-d", classes.toString(), source.toString()));

        Files.createDirectories(jar.getParent());
        try (java.util.jar.JarOutputStream output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar));
                var paths = Files.walk(classes)) {
            for (Path item : paths.filter(Files::isRegularFile).toList()) {
                java.util.jar.JarEntry entry = new java.util.jar.JarEntry(
                        classes.relativize(item).toString().replace('\\', '/'));
                output.putNextEntry(entry);
                Files.copy(item, output);
                output.closeEntry();
            }
        }
        return jar;
    }

    private void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "Symbolic links are not available: " + exception.getMessage());
        }
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

    private void assertNoWizDirectories(Path workspace) throws Exception {
        try (var paths = Files.walk(workspace)) {
            var hiddenDirectories = paths
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName() != null && path.getFileName().toString().equals(".wiz"))
                    .toList();
            assertTrue(hiddenDirectories.isEmpty(), "Unexpected .wiz directories: " + hiddenDirectories);
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
