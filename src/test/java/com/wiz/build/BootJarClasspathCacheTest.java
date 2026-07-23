package com.wiz.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootJarClasspathCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsBootClasspathOnceAndReusesValidatedContent() throws Exception {
        Path workspace = workspace("reuse-workspace");
        Path cache = tempDir.resolve("reuse-cache");
        Path jar = writeJar(tempDir.resolve("runtime.jar"), List.of(
                file("BOOT-INF/classes/example/Marker.class", "marker"),
                file("BOOT-INF/classes/example/config.txt", "config"),
                file("BOOT-INF/lib/group-a/shared.jar", "first-library"),
                file("BOOT-INF/lib/group-b/shared.jar", "second-library")));
        RecordingLogger logger = new RecordingLogger();
        BootJarClasspathCache service = new BootJarClasspathCache(cache);

        BootJarClasspathCache.Result first = service.resolve(workspace, jar, logger).orElseThrow();

        assertEquals(3, first.classpathEntries().size());
        Path classes = first.classpathEntries().get(0);
        Path firstLibrary = first.classpathEntries().get(1);
        Path secondLibrary = first.classpathEntries().get(2);
        assertEquals("marker", Files.readString(classes.resolve("example/Marker.class")));
        assertTrue(firstLibrary.endsWith("lib/group-a/shared.jar"));
        assertTrue(secondLibrary.endsWith("lib/group-b/shared.jar"));
        assertEquals("first-library", Files.readString(firstLibrary));
        assertEquals("second-library", Files.readString(secondLibrary));
        assertTrue(Files.isRegularFile(classes.getParent().resolve(".complete.json")));

        Path marker = classes.resolve("example/Marker.class");
        FileTime sentinel = FileTime.fromMillis(1_234_000L);
        Files.setLastModifiedTime(marker, sentinel);
        BootJarClasspathCache.Result second = service.resolve(workspace, jar, logger).orElseThrow();

        assertEquals(first.key(), second.key());
        assertEquals(first.classpathEntries(), second.classpathEntries());
        assertEquals(sentinel, Files.getLastModifiedTime(marker));
        assertTrue(logger.messages.stream().anyMatch(message -> message.contains("cache] hit")));
        assertNoStagingDirectories(cache);
    }

    @Test
    void detectsSameSizeCorruptionAndUnexpectedFilesThenReextracts() throws Exception {
        Path workspace = workspace("corruption-workspace");
        Path cache = tempDir.resolve("corruption-cache");
        Path jar = writeJar(tempDir.resolve("corruption-runtime.jar"), List.of(
                file("BOOT-INF/classes/example/Marker.class", "original"),
                file("BOOT-INF/lib/dependency.jar", "dependency")));
        BootJarClasspathCache service = new BootJarClasspathCache(cache);
        BootJarClasspathCache.Result initial = service.resolve(workspace, jar, BuildLogger.quiet()).orElseThrow();
        Path marker = initial.classpathEntries().get(0).resolve("example/Marker.class");
        Path entryRoot = marker.getParent().getParent().getParent();

        Files.writeString(marker, "tampered");
        Files.writeString(entryRoot.resolve("classes/unexpected.txt"), "unexpected");
        BootJarClasspathCache.Result repaired = service.resolve(workspace, jar, BuildLogger.quiet()).orElseThrow();

        assertEquals(initial.key(), repaired.key());
        assertEquals("original", Files.readString(repaired.classpathEntries().get(0).resolve("example/Marker.class")));
        assertFalse(Files.exists(repaired.classpathEntries().get(0).resolve("unexpected.txt")));
        assertNoStagingDirectories(cache);
    }

    @Test
    void sourceContentChangeUsesANewDigestAndPruneKeepsOnlyActiveKeys() throws Exception {
        Path workspace = workspace("source-change-workspace");
        Path cache = tempDir.resolve("source-change-cache");
        Path jar = writeJar(tempDir.resolve("changing-runtime.jar"), List.of(
                file("BOOT-INF/classes/example/Marker.class", "version-one")));
        BootJarClasspathCache service = new BootJarClasspathCache(cache);
        BootJarClasspathCache.Result first = service.resolve(workspace, jar, BuildLogger.quiet()).orElseThrow();

        writeJar(jar, List.of(file("BOOT-INF/classes/example/Marker.class", "version-two")));
        BootJarClasspathCache.Result second = service.resolve(workspace, jar, BuildLogger.quiet()).orElseThrow();

        assertNotEquals(first.key(), second.key());
        assertEquals("version-one", Files.readString(first.classpathEntries().get(0).resolve("example/Marker.class")));
        assertEquals("version-two", Files.readString(second.classpathEntries().get(0).resolve("example/Marker.class")));
        Path abandoned = cache.resolve("." + first.key() + ".next-abandoned");
        Files.createDirectories(abandoned);
        Files.writeString(abandoned.resolve("partial"), "partial");

        service.prune(workspace, Set.of(second.key()), BuildLogger.quiet());

        assertFalse(Files.exists(first.classpathEntries().get(0).getParent()));
        assertTrue(Files.isDirectory(second.classpathEntries().get(0)));
        assertFalse(Files.exists(abandoned));
    }

    @Test
    void multipleFatJarsUseIndependentContentAddressedRoots() throws Exception {
        Path workspace = workspace("multiple-workspace");
        Path cache = tempDir.resolve("multiple-cache");
        Path firstJar = writeJar(tempDir.resolve("first-runtime.jar"), List.of(
                file("BOOT-INF/classes/first/Type.class", "first-class"),
                file("BOOT-INF/lib/nested/shared.jar", "first-shared")));
        Path secondJar = writeJar(tempDir.resolve("second-runtime.jar"), List.of(
                file("BOOT-INF/classes/second/Type.class", "second-class"),
                file("BOOT-INF/lib/nested/shared.jar", "second-shared")));
        BootJarClasspathCache service = new BootJarClasspathCache(cache);

        BootJarClasspathCache.Result first = service.resolve(workspace, firstJar, BuildLogger.quiet()).orElseThrow();
        BootJarClasspathCache.Result second = service.resolve(workspace, secondJar, BuildLogger.quiet()).orElseThrow();

        assertNotEquals(first.key(), second.key());
        assertNotEquals(first.classpathEntries().get(0), second.classpathEntries().get(0));
        assertEquals("first-class", Files.readString(first.classpathEntries().get(0).resolve("first/Type.class")));
        assertEquals("second-class", Files.readString(second.classpathEntries().get(0).resolve("second/Type.class")));
        assertEquals("first-shared", Files.readString(first.classpathEntries().get(1)));
        assertEquals("second-shared", Files.readString(second.classpathEntries().get(1)));
    }

    @Test
    void rejectsTraversalAndLeavesNoPublishedOrStagedEntry() throws Exception {
        Path workspace = workspace("traversal-workspace");
        Path cache = tempDir.resolve("traversal-cache");
        Path jar = writeJar(tempDir.resolve("traversal-runtime.jar"), List.of(
                file("BOOT-INF/classes/../../escaped.txt", "escape")));
        BootJarClasspathCache service = new BootJarClasspathCache(cache);

        IOException failure = assertThrows(
                IOException.class,
                () -> service.resolve(workspace, jar, BuildLogger.quiet()));

        assertTrue(failure.getMessage().contains("unsafe cache path"));
        assertFalse(Files.exists(cache.resolve("escaped.txt")));
        try (Stream<Path> children = Files.list(cache)) {
            assertTrue(children.findAny().isEmpty());
        }
    }

    @Test
    void rejectsFileDirectoryCollisionsWithoutPublishingPartialCache() throws Exception {
        Path workspace = workspace("collision-workspace");
        Path cache = tempDir.resolve("collision-cache");
        Path jar = writeJar(tempDir.resolve("collision-runtime.jar"), List.of(
                file("BOOT-INF/classes/example", "file"),
                file("BOOT-INF/classes/example/Type.class", "class")));
        BootJarClasspathCache service = new BootJarClasspathCache(cache);

        IOException failure = assertThrows(
                IOException.class,
                () -> service.resolve(workspace, jar, BuildLogger.quiet()));

        assertTrue(failure.getMessage().contains("file/directory extraction collision"));
        try (Stream<Path> children = Files.list(cache)) {
            assertTrue(children.findAny().isEmpty());
        }
    }

    @Test
    void rejectsDuplicateArchiveTargetsWithoutPublishingPartialCache() throws Exception {
        Path workspace = workspace("duplicate-workspace");
        Path cache = tempDir.resolve("duplicate-cache");
        Path jar = tempDir.resolve("duplicate-runtime.jar");
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(jar)) {
            writeDuplicateCapableEntry(output, "BOOT-INF/classes/example/Type.class", "first");
            writeDuplicateCapableEntry(output, "BOOT-INF/classes/example/Type.class", "second");
        }
        BootJarClasspathCache service = new BootJarClasspathCache(cache);

        IOException failure = assertThrows(
                IOException.class,
                () -> service.resolve(workspace, jar, BuildLogger.quiet()));

        assertTrue(failure.getMessage().contains("duplicate extraction target"));
        try (Stream<Path> children = Files.list(cache)) {
            assertTrue(children.findAny().isEmpty());
        }
    }

    @Test
    void tamperedManifestCannotEscapeCacheAndIsReplaced() throws Exception {
        Path workspace = workspace("manifest-workspace");
        Path cache = tempDir.resolve("manifest-cache");
        Path jar = writeJar(tempDir.resolve("manifest-runtime.jar"), List.of(
                file("BOOT-INF/classes/example/Marker.class", "marker")));
        BootJarClasspathCache service = new BootJarClasspathCache(cache);
        BootJarClasspathCache.Result initial = service.resolve(workspace, jar, BuildLogger.quiet()).orElseThrow();
        Path entryRoot = initial.classpathEntries().get(0).getParent();
        Path manifest = entryRoot.resolve(".complete.json");
        String contents = Files.readString(manifest);
        Files.writeString(manifest, contents.replace(
                "classes/example/Marker.class",
                "classes/../outside-marker.bin"));

        BootJarClasspathCache.Result repaired = service.resolve(workspace, jar, BuildLogger.quiet()).orElseThrow();

        assertEquals("marker", Files.readString(repaired.classpathEntries().get(0).resolve("example/Marker.class")));
        assertFalse(Files.exists(cache.resolve("outside-marker.bin")));
        assertFalse(Files.readString(entryRoot.resolve(".complete.json")).contains("../"));
    }

    @Test
    void returnsEmptyForOrdinaryOrMissingJarsWithoutCreatingCache() throws Exception {
        Path workspace = workspace("ordinary-workspace");
        Path cache = tempDir.resolve("ordinary-cache");
        Path ordinary = writeJar(tempDir.resolve("ordinary.jar"), List.of(
                file("example/Type.class", "ordinary")));
        BootJarClasspathCache service = new BootJarClasspathCache(cache);

        assertTrue(service.resolve(workspace, ordinary, BuildLogger.quiet()).isEmpty());
        assertTrue(service.resolve(workspace, tempDir.resolve("missing.jar"), BuildLogger.quiet()).isEmpty());
        assertFalse(Files.exists(cache));
    }

    @Test
    void rejectsCacheRootInsideWorkspace() throws Exception {
        Path workspace = workspace("inside-cache-workspace");
        BootJarClasspathCache service = new BootJarClasspathCache(workspace.resolve("cache"));
        Path jar = writeJar(tempDir.resolve("inside-cache-runtime.jar"), List.of(
                file("BOOT-INF/classes/example/Type.class", "class")));

        assertThrows(IOException.class, () -> service.resolve(workspace, jar, BuildLogger.quiet()));
        assertFalse(Files.exists(workspace.resolve("cache")));
    }

    private Path workspace(String name) throws IOException {
        Path workspace = tempDir.resolve(name);
        Files.createDirectories(workspace);
        return workspace;
    }

    private Path writeJar(Path jar, List<TestEntry> entries) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (TestEntry item : entries) {
                JarEntry entry = new JarEntry(item.name());
                output.putNextEntry(entry);
                output.write(item.contents());
                output.closeEntry();
            }
        }
        return jar;
    }

    private TestEntry file(String name, String contents) {
        return new TestEntry(name, contents.getBytes(StandardCharsets.UTF_8));
    }

    private void writeDuplicateCapableEntry(ZipArchiveOutputStream output, String name, String contents)
            throws IOException {
        output.putArchiveEntry(new ZipArchiveEntry(name));
        output.write(contents.getBytes(StandardCharsets.UTF_8));
        output.closeArchiveEntry();
    }

    private void assertNoStagingDirectories(Path cache) throws IOException {
        try (Stream<Path> children = Files.list(cache)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString().contains(".next-")));
        }
    }

    private record TestEntry(String name, byte[] contents) {
        private TestEntry {
            contents = contents.clone();
        }

        @Override
        public byte[] contents() {
            return contents.clone();
        }
    }

    private static final class RecordingLogger implements BuildLogger {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void info(String message) {
            messages.add(message);
        }

        @Override
        public void output(String text) {
        }
    }
}
