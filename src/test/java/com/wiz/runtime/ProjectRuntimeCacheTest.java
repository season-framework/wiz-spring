package com.wiz.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;

import com.wiz.build.BuildResult;
import com.wiz.build.ProjectBuildService;
import com.wiz.core.ProjectJavaNaming;
import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.ObjectMapper;

class ProjectRuntimeCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void reusesRuntimeAndApiMethodWithinSameBuildMarker() throws Exception {
        ProjectContext project = projectWithApi("one");
        ProjectRuntimeCache cache = new ProjectRuntimeCache();

        ProjectRuntimeCache.CachedProjectRuntime firstRuntime = cache.get(project);
        ProjectRuntimeCache.CachedProjectRuntime secondRuntime = cache.get(project);
        var firstHandler = firstRuntime.apiHandler(ProjectJavaNaming.appApiHandlerClass(project.name(), "page.dashboard"), "version").orElseThrow();
        var secondHandler = secondRuntime.apiHandler(ProjectJavaNaming.appApiHandlerClass(project.name(), "page.dashboard"), "version").orElseThrow();

        assertSame(firstRuntime, secondRuntime);
        assertSame(firstHandler, secondHandler);
    }

    @Test
    void invalidatesProjectRuntimeAndClosesClassLoaderWhenBuildMarkerChanges() throws Exception {
        ProjectContext project = projectWithApi("one");
        AtomicInteger closeCount = new AtomicInteger();
        ProjectRuntimeCache cache = new ProjectRuntimeCache(new ObjectMapper(), "test", (urls, parent) -> countingClassLoader(urls, parent, closeCount));

        ProjectRuntimeCache.CachedProjectRuntime firstRuntime = cache.get(project);
        AtomicInteger hookCount = new AtomicInteger();
        firstRuntime.onClose(hookCount::incrementAndGet);
        Files.writeString(project.appRoot().resolve("page.dashboard/api.java"), versionApi("two"));
        BuildResult rebuild = new ProjectBuildService().build(project, true, "bundle");
        assertTrue(rebuild.success(), rebuild.message());

        ProjectRuntimeCache.CachedProjectRuntime secondRuntime = cache.get(project);

        assertNotSame(firstRuntime, secondRuntime);
        assertEquals(1, closeCount.get());
        assertEquals(1, hookCount.get());
        cache.close();
        assertEquals(2, closeCount.get());
        assertEquals(1, hookCount.get());
    }

    @Test
    void invalidatesProjectRuntimeWhenCompiledArtifactsChangeEvenIfBuildMarkerIsRestored() throws Exception {
        ProjectContext project = projectWithApi("one");
        AtomicInteger closeCount = new AtomicInteger();
        ProjectRuntimeCache cache = new ProjectRuntimeCache(new ObjectMapper(), "test", (urls, parent) -> countingClassLoader(urls, parent, closeCount));
        Path marker = project.bundleRoot().resolve(BuildMarkerService.MARKER_FILE);
        String markerContents = Files.readString(marker);
        FileTime markerTime = Files.getLastModifiedTime(marker);

        ProjectRuntimeCache.CachedProjectRuntime firstRuntime = cache.get(project);
        assertEquals("one", invokeVersion(project, firstRuntime));

        Files.writeString(project.appRoot().resolve("page.dashboard/api.java"), versionApi("two"));
        BuildResult rebuild = new ProjectBuildService().build(project, true, "bundle");
        assertTrue(rebuild.success(), rebuild.message());
        Files.writeString(marker, markerContents);
        Files.setLastModifiedTime(marker, markerTime);

        ProjectRuntimeCache.CachedProjectRuntime secondRuntime = cache.get(project);

        assertNotSame(firstRuntime, secondRuntime);
        assertEquals("two", invokeVersion(project, secondRuntime));
        assertEquals(1, closeCount.get());
    }

    @Test
    void productionProfileUsesBuildMarkerWithoutWalkingCompiledArtifacts() throws Exception {
        ProjectContext project = projectWithApi("one");
        ProjectRuntimeCache cache = new ProjectRuntimeCache(new ObjectMapper(), "prod", URLClassLoader::new);
        Path marker = project.bundleRoot().resolve(BuildMarkerService.MARKER_FILE);
        String markerContents = Files.readString(marker);
        FileTime markerTime = Files.getLastModifiedTime(marker);

        ProjectRuntimeCache.CachedProjectRuntime firstRuntime = cache.get(project);
        assertEquals("one", invokeVersion(project, firstRuntime));

        Files.writeString(project.appRoot().resolve("page.dashboard/api.java"), versionApi("two"));
        BuildResult rebuild = new ProjectBuildService().build(project, true, "bundle");
        assertTrue(rebuild.success(), rebuild.message());
        Files.writeString(marker, markerContents);
        Files.setLastModifiedTime(marker, markerTime);

        ProjectRuntimeCache.CachedProjectRuntime secondRuntime = cache.get(project);

        assertSame(firstRuntime, secondRuntime);
        assertEquals("one", invokeVersion(project, secondRuntime));
    }

    private ProjectContext projectWithApi(String version) throws Exception {
        Path workspace = tempDir.resolve("workspace-" + version + "-" + java.util.UUID.randomUUID());
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        Files.writeString(project.appRoot().resolve("page.dashboard/api.java"), versionApi(version));
        BuildResult build = new ProjectBuildService().build(project, true, "bundle");
        assertTrue(build.success(), build.message());
        return project;
    }

    private String versionApi(String version) {
        return "public final class PageDashboardApi {\n"
                + "    public String version() { return \"" + version + "\"; }\n"
                + "}\n";
    }

    private String invokeVersion(ProjectContext project, ProjectRuntimeCache.CachedProjectRuntime runtime) throws Exception {
        var handler = runtime.apiHandler(ProjectJavaNaming.appApiHandlerClass(project.name(), "page.dashboard"), "version").orElseThrow();
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(runtime.classLoader());
        try {
            Object instance = handler.constructor().newInstance();
            return handler.method().invoke(instance).toString();
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }

    private URLClassLoader countingClassLoader(URL[] urls, ClassLoader parent, AtomicInteger closeCount) {
        return new URLClassLoader(urls, parent) {
            @Override
            public void close() throws IOException {
                closeCount.incrementAndGet();
                super.close();
            }
        };
    }
}
