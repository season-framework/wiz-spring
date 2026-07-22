package com.wiz.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
        var firstHandler = firstRuntime.apiHandler(ProjectJavaNaming.appApiHandlerClass(project, "page.dashboard"), "version").orElseThrow();
        var secondHandler = secondRuntime.apiHandler(ProjectJavaNaming.appApiHandlerClass(project, "page.dashboard"), "version").orElseThrow();

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
    void keepsLastCompletedMarkerRuntimeWhileBundleMarkerIsTemporarilyMissing() throws Exception {
        ProjectContext project = projectWithApi("one");
        ProjectRuntimeCache cache = new ProjectRuntimeCache();
        ProjectRuntimeCache.CachedProjectRuntime completed = cache.get(project);
        Path marker = project.bundleRoot().resolve(BuildMarkerService.MARKER_FILE);
        Path savedMarker = project.bundleRoot().resolve("saved-build-marker.json");
        Files.move(marker, savedMarker);

        try {
            assertSame(completed, cache.get(project));
        } finally {
            Files.move(savedMarker, marker);
            cache.close();
        }
    }

    @Test
    void discardsSnapshotWhenBuildMarkerDisappearsAndKeepsCompletedRuntime() throws Exception {
        ProjectContext project = projectWithApi("one");
        Path marker = project.bundleRoot().resolve(BuildMarkerService.MARKER_FILE);
        CountDownLatch snapshotReady = new CountDownLatch(1);
        CountDownLatch markerRemoved = new CountDownLatch(1);
        AtomicInteger createCount = new AtomicInteger();
        AtomicInteger closeCount = new AtomicInteger();
        AtomicReference<Path> discardedSnapshotEntry = new AtomicReference<>();
        ProjectRuntimeCache cache = new ProjectRuntimeCache(new ObjectMapper(), "test", (urls, parent) -> {
            int creation = createCount.incrementAndGet();
            URLClassLoader loader = countingClassLoader(urls, parent, closeCount);
            if (creation == 2) {
                discardedSnapshotEntry.set(Path.of(urls[0].getPath()));
                snapshotReady.countDown();
                try {
                    if (!markerRemoved.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for marker replacement");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while simulating marker replacement", exception);
                }
            }
            return loader;
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String completedMarker = null;
        FileTime completedMarkerTime = null;
        try {
            ProjectRuntimeCache.CachedProjectRuntime firstRuntime = cache.get(project);
            Files.writeString(project.appRoot().resolve("page.dashboard/api.java"), versionApi("two"));
            BuildResult rebuild = new ProjectBuildService().build(project, true, "bundle");
            assertTrue(rebuild.success(), rebuild.message());
            completedMarker = Files.readString(marker);
            completedMarkerTime = Files.getLastModifiedTime(marker);

            Future<ProjectRuntimeCache.CachedProjectRuntime> loading = executor.submit(() -> cache.get(project));
            assertTrue(snapshotReady.await(10, TimeUnit.SECONDS));
            Files.delete(marker);
            markerRemoved.countDown();

            ProjectRuntimeCache.CachedProjectRuntime duringReplacement = loading.get(10, TimeUnit.SECONDS);
            assertSame(firstRuntime, duringReplacement);
            assertEquals("one", invokeVersion(project, duringReplacement));
            assertEquals(1, closeCount.get());
            assertFalse(Files.exists(discardedSnapshotEntry.get()));

            Files.writeString(marker, completedMarker);
            Files.setLastModifiedTime(marker, completedMarkerTime);
            ProjectRuntimeCache.CachedProjectRuntime replacement = cache.get(project);

            assertNotSame(firstRuntime, replacement);
            assertEquals("two", invokeVersion(project, replacement));
            assertEquals(2, closeCount.get());
        } finally {
            markerRemoved.countDown();
            executor.shutdownNow();
            if (completedMarker != null && Files.notExists(marker)) {
                Files.writeString(marker, completedMarker);
                Files.setLastModifiedTime(marker, completedMarkerTime);
            }
            cache.close();
        }
    }

    @Test
    void keepsRetiredRuntimeOpenUntilActiveRequestLeaseIsReleased() throws Exception {
        ProjectContext project = projectWithApi("one");
        AtomicInteger closeCount = new AtomicInteger();
        AtomicInteger hookCount = new AtomicInteger();
        ProjectRuntimeCache cache = new ProjectRuntimeCache(new ObjectMapper(), "test", (urls, parent) -> countingClassLoader(urls, parent, closeCount));

        ProjectRuntimeCache.RuntimeLease firstLease = cache.acquire(project);
        ProjectRuntimeCache.CachedProjectRuntime firstRuntime = firstLease.runtime();
        Path firstSnapshotEntry = Path.of(((URLClassLoader) firstRuntime.classLoader()).getURLs()[0].toURI());
        firstRuntime.onClose(hookCount::incrementAndGet);
        Files.writeString(project.appRoot().resolve("page.dashboard/api.java"), versionApi("two"));
        BuildResult rebuild = new ProjectBuildService().build(project, true, "bundle");
        assertTrue(rebuild.success(), rebuild.message());

        ProjectRuntimeCache.RuntimeLease secondLease = cache.acquire(project);
        ProjectRuntimeCache.CachedProjectRuntime secondRuntime = secondLease.runtime();

        assertNotSame(firstRuntime, secondRuntime);
        assertEquals(0, closeCount.get());
        assertEquals(0, hookCount.get());
        assertTrue(Files.exists(firstSnapshotEntry));
        assertEquals("one", invokeVersion(project, firstRuntime));

        firstLease.close();
        assertEquals(1, closeCount.get());
        assertEquals(1, hookCount.get());
        assertTrue(Files.notExists(firstSnapshotEntry));
        secondLease.close();
        cache.close();
        assertEquals(2, closeCount.get());
    }

    @Test
    void usesStableRuntimeClassLoaderParentInsteadOfCallingThreadContextLoader() throws Exception {
        ProjectContext project = projectWithApi("one");
        AtomicReference<ClassLoader> observedParent = new AtomicReference<>();
        ProjectRuntimeCache cache = new ProjectRuntimeCache(new ObjectMapper(), "test", (urls, parent) -> {
            observedParent.set(parent);
            return new URLClassLoader(urls, parent);
        });
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader transientRequestLoader = new URLClassLoader(new URL[0], previousLoader)) {
            Thread.currentThread().setContextClassLoader(transientRequestLoader);

            cache.get(project);

            assertNotSame(transientRequestLoader, observedParent.get());
            assertSame(ProjectRuntimeCache.class.getClassLoader(), observedParent.get());
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
            cache.close();
        }
    }

    @Test
    void cleanupFailureDoesNotBlockReplacementRuntime() throws Exception {
        ProjectContext project = projectWithApi("one");
        ProjectRuntimeCache cache = new ProjectRuntimeCache();
        ProjectRuntimeCache.CachedProjectRuntime firstRuntime = cache.get(project);
        firstRuntime.onClose(() -> {
            throw new IOException("simulated cleanup failure");
        });
        Files.writeString(project.appRoot().resolve("page.dashboard/api.java"), versionApi("two"));
        BuildResult rebuild = new ProjectBuildService().build(project, true, "bundle");
        assertTrue(rebuild.success(), rebuild.message());

        ProjectRuntimeCache.CachedProjectRuntime replacement = assertDoesNotThrow(() -> cache.get(project));

        assertNotSame(firstRuntime, replacement);
        assertEquals("two", invokeVersion(project, replacement));
        cache.close();
    }

    @Test
    void defaultProfileUsesBuildMarkerWithoutWalkingCompiledArtifacts() throws Exception {
        ProjectContext project = projectWithApi("one");
        ProjectRuntimeCache cache = new ProjectRuntimeCache(new ObjectMapper(), "test", URLClassLoader::new);
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
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
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
        var handler = runtime.apiHandler(ProjectJavaNaming.appApiHandlerClass(project, "page.dashboard"), "version").orElseThrow();
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
