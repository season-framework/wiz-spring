package com.wiz.runtime;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.wiz.core.ProjectJavaNaming;
import com.wiz.dispatch.ControllerChain;
import com.wiz.dispatch.ControllerHook;
import com.wiz.dispatch.RouteDefinition;
import com.wiz.dispatch.RouteHandler;
import com.wiz.socket.SocketController;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProjectRuntimeCache implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectRuntimeCache.class);
    private static final int MAX_RUNTIME_LOAD_ATTEMPTS = 3;

    private final ConcurrentHashMap<RuntimeKey, CachedProjectRuntime> runtimes = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final List<String> profiles;
    private final String profile;
    private final ClassLoaderFactory classLoaderFactory;
    private final ClassLoader runtimeParentClassLoader;

    @Autowired
    public ProjectRuntimeCache(Environment environment) {
        this(new ObjectMapper(), activeProfiles(environment), URLClassLoader::new);
    }

    public ProjectRuntimeCache() {
        this(new ObjectMapper(), "default", URLClassLoader::new);
    }

    public ProjectRuntimeCache(ObjectMapper objectMapper) {
        this(objectMapper, "default", URLClassLoader::new);
    }

    ProjectRuntimeCache(ObjectMapper objectMapper, String profile, ClassLoaderFactory classLoaderFactory) {
        this(objectMapper, profiles(profile), classLoaderFactory);
    }

    private ProjectRuntimeCache(ObjectMapper objectMapper, List<String> profiles, ClassLoaderFactory classLoaderFactory) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.profiles = profiles == null || profiles.isEmpty() ? List.of("default") : List.copyOf(profiles);
        this.profile = String.join(",", this.profiles);
        this.classLoaderFactory = classLoaderFactory == null ? URLClassLoader::new : classLoaderFactory;
        this.runtimeParentClassLoader = stableRuntimeParentClassLoader();
    }

    public CachedProjectRuntime get(ProjectContext project) {
        return currentRuntime(project);
    }

    public RuntimeLease acquire(ProjectContext project) {
        synchronized (runtimes) {
            return currentRuntimeLocked(project).acquire();
        }
    }

    private CachedProjectRuntime currentRuntime(ProjectContext project) {
        synchronized (runtimes) {
            return currentRuntimeLocked(project);
        }
    }

    private CachedProjectRuntime currentRuntimeLocked(ProjectContext project) {
        RuntimeIdentity identity = identity(project);
        CachedProjectRuntime fallback = runtimeForIdentity(identity);
        BuildReadGuard acquiredGuard;
        try {
            acquiredGuard = BuildReadGuard.tryAcquire(project);
        } catch (IOException exception) {
            LOGGER.warn("Could not acquire WIZ build lock while loading project runtime; marker validation will still be used: project={}",
                    project.name(), exception);
            acquiredGuard = BuildReadGuard.unlocked();
        }
        BuildReadGuard guard = acquiredGuard;
        if (guard == null) {
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalStateException("Project build is in progress and no completed runtime is available: " + project.name());
        }
        try (guard) {
            return loadStableRuntime(project, identity, fallback);
        }
    }

    private CachedProjectRuntime loadStableRuntime(ProjectContext project, RuntimeIdentity identity, CachedProjectRuntime fallback) {
        CachedProjectRuntime markerFallback = markerRuntimeForIdentity(identity);
        for (int attempt = 1; attempt <= MAX_RUNTIME_LOAD_ATTEMPTS; attempt++) {
            RuntimeObservation before = observeRuntime(project, identity, markerFallback == null);
            if (!before.stable()) {
                if (fallback != null) {
                    return fallback;
                }
                if (attempt < MAX_RUNTIME_LOAD_ATTEMPTS) {
                    Thread.yield();
                    continue;
                }
                throw new IllegalStateException("Project runtime artifacts are being replaced: " + project.name()
                        + " (" + before.reason() + ")");
            }

            RuntimeKey key = before.key();
            CachedProjectRuntime existing = runtimes.get(key);
            if (existing != null) {
                return existing;
            }

            CachedProjectRuntime created;
            try {
                created = createRuntime(project, key);
            } catch (RuntimeException exception) {
                RuntimeObservation afterFailure = observeRuntime(project, identity, markerFallback == null);
                if (!afterFailure.stable() || !key.equals(afterFailure.key())) {
                    if (attempt < MAX_RUNTIME_LOAD_ATTEMPTS) {
                        continue;
                    }
                } else if (fallback == null) {
                    throw exception;
                }
                if (fallback != null) {
                    LOGGER.warn("Failed to load replacement WIZ project runtime; continuing with the last completed runtime: project={} version={}",
                            key.projectName(), shortVersion(key.version()), exception);
                    return fallback;
                }
                throw exception;
            }

            RuntimeObservation after = observeRuntime(project, identity, markerFallback == null);
            if (after.stable() && key.equals(after.key())) {
                runtimes.put(key, created);
                evictStaleProjectEntries(key);
                LOGGER.info("Loaded WIZ project runtime: project={} version={} profile={}",
                        key.projectName(), shortVersion(key.version()), profile);
                return created;
            }

            String reason = after.stable() ? "runtime version changed after snapshot creation" : after.reason();
            discardUnstableRuntime(created, key, reason);
            fallback = runtimeForIdentity(identity);
            markerFallback = markerRuntimeForIdentity(identity);
            if (!after.stable() && fallback != null) {
                return fallback;
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new IllegalStateException("Project runtime artifacts did not remain stable while creating a snapshot: " + project.name());
    }

    private void discardUnstableRuntime(CachedProjectRuntime runtime, RuntimeKey key, String reason) {
        LOGGER.info("Discarding WIZ project runtime snapshot because build artifacts changed: project={} version={} reason={}",
                key.projectName(), shortVersion(key.version()), reason);
        try {
            runtime.close();
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to close discarded WIZ project runtime snapshot: project={}", key.projectName(), exception);
        }
    }

    private CachedProjectRuntime runtimeForIdentity(RuntimeIdentity identity) {
        return runtimes.entrySet().stream()
                .filter(entry -> entry.getKey().identity().equals(identity))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private CachedProjectRuntime markerRuntimeForIdentity(RuntimeIdentity identity) {
        return runtimes.entrySet().stream()
                .filter(entry -> entry.getKey().identity().equals(identity))
                .filter(entry -> entry.getKey().version().startsWith("marker:"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    public void invalidate(ProjectContext project) {
        RuntimeIdentity identity = identity(project);
        synchronized (runtimes) {
            evict(identity);
        }
    }

    @PreDestroy
    @Override
    public void close() {
        synchronized (runtimes) {
            List<CachedProjectRuntime> values = List.copyOf(runtimes.values());
            runtimes.clear();
            for (CachedProjectRuntime runtime : values) {
                try {
                    runtime.close();
                } catch (RuntimeException exception) {
                    LOGGER.warn("Failed to close project runtime during shutdown", exception);
                }
            }
        }
    }

    private CachedProjectRuntime createRuntime(ProjectContext project, RuntimeKey key) {
        RuntimeSnapshot snapshot = null;
        try {
            snapshot = RuntimeSnapshot.create(project, key.version());
            URL[] urls = snapshot.classPathUrls();
            URLClassLoader classLoader = classLoaderFactory.create(urls, runtimeParentClassLoader);
            return new CachedProjectRuntime(snapshot.project(), objectMapper, classLoader, snapshot, key, profiles);
        } catch (IOException exception) {
            closeFailedSnapshot(snapshot, exception);
            throw new IllegalStateException("Failed to create project runtime cache: " + key.projectName(), exception);
        } catch (RuntimeException | Error exception) {
            closeFailedSnapshot(snapshot, exception);
            throw exception;
        }
    }

    private void closeFailedSnapshot(RuntimeSnapshot snapshot, Throwable failure) {
        if (snapshot == null) {
            return;
        }
        try {
            snapshot.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static ClassLoader stableRuntimeParentClassLoader() {
        ClassLoader loader = ProjectRuntimeCache.class.getClassLoader();
        return loader == null ? ClassLoader.getSystemClassLoader() : loader;
    }

    private void evictStaleProjectEntries(RuntimeKey currentKey) {
        evict(currentKey.identity(), currentKey);
    }

    private void evict(RuntimeIdentity identity) {
        evict(identity, null);
    }

    private void evict(RuntimeIdentity identity, RuntimeKey keep) {
        List<RuntimeKey> stale = runtimes.keySet().stream()
                .filter(key -> key.identity().equals(identity))
                .filter(key -> keep == null || !key.equals(keep))
                .toList();
        for (RuntimeKey key : stale) {
            CachedProjectRuntime runtime = runtimes.remove(key);
            if (runtime != null) {
                LOGGER.info("Retiring WIZ project runtime: project={} version={}",
                        key.projectName(), shortVersion(key.version()));
                try {
                    runtime.close();
                } catch (RuntimeException exception) {
                    LOGGER.warn("Failed to close retired project runtime: {}", key.projectName(), exception);
                }
            }
        }
    }

    private RuntimeIdentity identity(ProjectContext project) {
        return new RuntimeIdentity(workspaceRoot(project), project.root().toAbsolutePath().normalize(), project.name(), profile);
    }

    private Path workspaceRoot(ProjectContext project) {
        Path projectRoot = project.root().toAbsolutePath().normalize();
        Path projectsRoot = projectRoot.getParent();
        if (projectsRoot != null && projectsRoot.getFileName() != null && projectsRoot.getFileName().toString().equals("project")) {
            Path workspaceRoot = projectsRoot.getParent();
            if (workspaceRoot != null) {
                return workspaceRoot.toAbsolutePath().normalize();
            }
        }
        return projectRoot;
    }

    private RuntimeObservation observeRuntime(ProjectContext project, RuntimeIdentity identity, boolean allowLegacy) {
        BuildMarkerToken marker = readBuildMarker(project);
        if (marker.complete()) {
            return RuntimeObservation.stable(new RuntimeKey(identity, project.name(), marker.version()));
        }
        if (marker.unstable()) {
            return RuntimeObservation.unstable(marker.reason());
        }
        if (!allowLegacy) {
            return RuntimeObservation.unstable("completed build marker is temporarily missing");
        }

        String runtimeArtifactFingerprint = runtimeArtifactFingerprint(project);
        String version = "mtime:" + runtimeArtifactFingerprint + ":source:" + fingerprint(project.sourceRoot());
        BuildMarkerToken afterFingerprint = readBuildMarker(project);
        if (!marker.equals(afterFingerprint)) {
            return RuntimeObservation.unstable("build marker changed while runtime artifacts were fingerprinted");
        }
        return RuntimeObservation.stable(new RuntimeKey(identity, project.name(), version));
    }

    private BuildMarkerToken readBuildMarker(ProjectContext project) {
        Path marker = project.bundleRoot().resolve(BuildMarkerService.MARKER_FILE);
        try {
            BasicFileAttributes before = Files.readAttributes(marker, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile()) {
                return BuildMarkerToken.unstable("build marker is not a regular file");
            }
            byte[] contents = Files.readAllBytes(marker);
            BasicFileAttributes after = Files.readAttributes(marker, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!sameMarkerFile(before, after)) {
                return BuildMarkerToken.unstable("build marker changed while it was read");
            }
            Map<String, Object> value = objectMapper.readValue(contents, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            if (!value.containsKey("buildFinishedAt") || !value.containsKey("buildPhases")) {
                return BuildMarkerToken.unstable("build marker is incomplete");
            }
            return BuildMarkerToken.complete("marker:" + after.lastModifiedTime().toMillis() + ":" + digest(contents));
        } catch (NoSuchFileException exception) {
            return BuildMarkerToken.missing();
        } catch (IOException | RuntimeException exception) {
            return BuildMarkerToken.unstable("build marker cannot be read");
        }
    }

    private boolean sameMarkerFile(BasicFileAttributes first, BasicFileAttributes second) {
        return first.isRegularFile() == second.isRegularFile()
                && first.size() == second.size()
                && first.lastModifiedTime().equals(second.lastModifiedTime())
                && Objects.equals(first.fileKey(), second.fileKey());
    }

    private String runtimeArtifactFingerprint(ProjectContext project) {
        return Stream.of(
                project.bundleRoot().resolve("classes"),
                project.bundleRoot().resolve("app-api.jar"),
                project.bundleRoot().resolve("lib"),
                project.bundleRoot().resolve("src/app"),
                project.bundleRoot().resolve("src/route"))
                .map(path -> path.getFileName() + "=" + fingerprint(path))
                .collect(Collectors.joining("|"));
    }

    private String fingerprint(Path path) {
        if (!Files.exists(path)) {
            return "missing";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateFingerprint(digest, path);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            return "mtime:" + maxModifiedTime(path);
        }
    }

    private void updateFingerprint(MessageDigest digest, Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            updateDigest(digest, path.getFileName().toString());
            updateDigest(digest, Long.toString(Files.size(path)));
            updateDigest(digest, Long.toString(modifiedTime(path)));
            return;
        }
        if (!Files.isDirectory(path)) {
            updateDigest(digest, "other");
            updateDigest(digest, Long.toString(modifiedTime(path)));
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path file : paths
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList()) {
                updateDigest(digest, path.relativize(file).toString().replace('\\', '/'));
                updateDigest(digest, Long.toString(Files.size(file)));
                updateDigest(digest, Long.toString(modifiedTime(file)));
            }
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private long maxModifiedTime(Path path) {
        if (!Files.exists(path)) {
            return 0L;
        }
        if (Files.isRegularFile(path)) {
            return modifiedTime(path);
        }
        if (!Files.isDirectory(path)) {
            return 0L;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            return paths.filter(Files::isRegularFile)
                    .mapToLong(this::modifiedTime)
                    .max()
                    .orElse(0L);
        } catch (IOException exception) {
            return modifiedTime(path);
        }
    }

    private long modifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    private String digest(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String shortVersion(String version) {
        if (version == null || version.length() <= 48) {
            return String.valueOf(version);
        }
        return version.substring(0, 48) + "...";
    }

    private static List<String> activeProfiles(Environment environment) {
        if (environment == null) {
            return List.of("default");
        }
        String[] active = environment.getActiveProfiles();
        if (active.length == 0) {
            active = environment.getDefaultProfiles();
        }
        if (active.length == 0) {
            return List.of("default");
        }
        return Arrays.stream(active)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static List<String> profiles(String profile) {
        if (profile == null || profile.isBlank()) {
            return List.of("default");
        }
        List<String> selected = Arrays.stream(profile.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        return selected.isEmpty() ? List.of("default") : selected;
    }


    @FunctionalInterface
    interface ClassLoaderFactory {
        URLClassLoader create(URL[] urls, ClassLoader parent);
    }

    private record RuntimeIdentity(Path workspaceRoot, Path projectRoot, String projectName, String profile) {
    }

    private record RuntimeKey(RuntimeIdentity identity, String projectName, String version) {
    }

    private enum BuildMarkerState {
        COMPLETE,
        MISSING,
        UNSTABLE
    }

    private record BuildMarkerToken(BuildMarkerState state, String version, String reason) {

        private static BuildMarkerToken complete(String version) {
            return new BuildMarkerToken(BuildMarkerState.COMPLETE, version, "stable build marker");
        }

        private static BuildMarkerToken missing() {
            return new BuildMarkerToken(BuildMarkerState.MISSING, null, "build marker is missing");
        }

        private static BuildMarkerToken unstable(String reason) {
            return new BuildMarkerToken(BuildMarkerState.UNSTABLE, null, reason);
        }

        private boolean complete() {
            return state == BuildMarkerState.COMPLETE;
        }

        private boolean unstable() {
            return state == BuildMarkerState.UNSTABLE;
        }
    }

    private record RuntimeObservation(RuntimeKey key, String reason) {

        private static RuntimeObservation stable(RuntimeKey key) {
            return new RuntimeObservation(key, "stable runtime artifacts");
        }

        private static RuntimeObservation unstable(String reason) {
            return new RuntimeObservation(null, reason);
        }

        private boolean stable() {
            return key != null;
        }
    }

    private static final class BuildReadGuard implements AutoCloseable {

        private final FileChannel channel;
        private final FileLock lock;

        private BuildReadGuard(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        private static BuildReadGuard tryAcquire(ProjectContext project) throws IOException {
            Path lockFile = project.root().toAbsolutePath().normalize().resolve(".wiz/build.lock");
            Files.createDirectories(lockFile.getParent());
            FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    closeQuietly(channel);
                    return null;
                }
                return new BuildReadGuard(channel, lock);
            } catch (OverlappingFileLockException exception) {
                closeQuietly(channel);
                return null;
            } catch (IOException | RuntimeException exception) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                throw exception;
            }
        }

        private static BuildReadGuard unlocked() {
            return new BuildReadGuard(null, null);
        }

        private static void closeQuietly(FileChannel channel) {
            try {
                channel.close();
            } catch (IOException exception) {
                LOGGER.debug("Failed to close WIZ build lock probe", exception);
            }
        }

        @Override
        public void close() {
            IOException failure = null;
            if (lock != null) {
                try {
                    lock.release();
                } catch (IOException exception) {
                    failure = exception;
                }
            }
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (failure != null) {
                LOGGER.warn("Failed to release WIZ project runtime build guard", failure);
            }
        }
    }

    private record ApiHandlerKey(String handlerClass, String function) {
    }

    private record RuntimeSnapshot(Path root, ProjectContext project, URL[] classPathUrls) implements AutoCloseable {

        private static RuntimeSnapshot create(ProjectContext project, String version) throws IOException {
            Path snapshotsRoot = project.root().resolve(".wiz/runtime-snapshots");
            long processId = ProcessHandle.current().pid();
            cleanupDeadProcessSnapshots(snapshotsRoot, processId);
            Path snapshots = snapshotsRoot.resolve(Long.toString(processId));
            Files.createDirectories(snapshots);
            String prefix = snapshotPrefix(version);
            Path root = snapshots.resolve(prefix + "-" + UUID.randomUUID()).toAbsolutePath().normalize();
            Files.createDirectories(root);
            try {
                Path bundle = root.resolve("bundle");
                linkTreeIfPresent(project.bundleRoot().resolve("src/app"), bundle.resolve("src/app"));
                linkTreeIfPresent(project.bundleRoot().resolve("src/route"), bundle.resolve("src/route"));
                linkTreeIfPresent(project.bundleRoot().resolve("config"), bundle.resolve("config"));

                List<Path> entries = ProjectClassPath.apiEntries(project);
                ArrayList<URL> urls = new ArrayList<>();
                for (int index = 0; index < entries.size(); index++) {
                    Path source = entries.get(index);
                    Path target = root.resolve("classpath")
                            .resolve(String.format(Locale.ROOT, "%03d-%s", index, source.getFileName()));
                    if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                        linkTree(source, target);
                    } else {
                        linkFile(source, target);
                    }
                    urls.add(target.toUri().toURL());
                }
                ProjectContext snapshotProject = new ProjectContext(
                        project.name(),
                        project.packageRoot(),
                        project.root(),
                        project.sourceRoot(),
                        project.appRoot(),
                        project.modelRoot(),
                        project.routeRoot(),
                        project.assetsRoot(),
                        bundle.resolve("config"),
                        project.buildRoot(),
                        bundle);
                return new RuntimeSnapshot(root, snapshotProject, urls.toArray(URL[]::new));
            } catch (IOException | RuntimeException | Error exception) {
                try {
                    deleteTree(root);
                } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                throw exception;
            }
        }

        private static String snapshotPrefix(String version) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] value = digest.digest(String.valueOf(version).getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(value, 0, 8);
            } catch (NoSuchAlgorithmException exception) {
                return Integer.toUnsignedString(String.valueOf(version).hashCode(), 16);
            }
        }

        private static void cleanupDeadProcessSnapshots(Path snapshotsRoot, long currentProcessId) {
            if (!Files.isDirectory(snapshotsRoot, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            try (Stream<Path> children = Files.list(snapshotsRoot)) {
                for (Path child : children.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    long processId;
                    try {
                        processId = Long.parseLong(child.getFileName().toString());
                    } catch (NumberFormatException exception) {
                        continue;
                    }
                    if (processId == currentProcessId || processAlive(processId)) {
                        continue;
                    }
                    try {
                        deleteTree(child);
                    } catch (IOException exception) {
                        LOGGER.warn("Failed to delete abandoned project runtime snapshot: {}", child, exception);
                    }
                }
            } catch (IOException exception) {
                LOGGER.warn("Failed to scan abandoned project runtime snapshots: {}", snapshotsRoot, exception);
            }
        }

        private static boolean processAlive(long processId) {
            try {
                return ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false);
            } catch (SecurityException exception) {
                return true;
            }
        }

        private static void linkTreeIfPresent(Path source, Path target) throws IOException {
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                linkTree(source, target);
            }
        }

        private static void linkTree(Path source, Path target) throws IOException {
            try (Stream<Path> paths = Files.walk(source)) {
                for (Path item : paths.toList()) {
                    Path relative = source.relativize(item);
                    Path destination = target.resolve(relative.toString()).normalize();
                    if (!destination.startsWith(target.normalize())) {
                        throw new IllegalArgumentException("Runtime snapshot escapes target directory");
                    }
                    if (Files.isSymbolicLink(item)) {
                        throw new IllegalArgumentException("Symbolic links are not allowed in runtime snapshots: " + relative);
                    }
                    if (Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(destination);
                    } else {
                        linkFile(item, destination);
                    }
                }
            }
        }

        private static void linkFile(Path source, Path target) throws IOException {
            if (Files.isSymbolicLink(source)) {
                throw new IllegalArgumentException("Symbolic links are not allowed in runtime snapshots: " + source.getFileName());
            }
            Files.createDirectories(target.getParent());
            try {
                Files.createLink(target, source);
            } catch (UnsupportedOperationException | IOException exception) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        @Override
        public void close() throws IOException {
            deleteTree(root);
            Path processRoot = root.getParent();
            if (processRoot != null) {
                try {
                    Files.deleteIfExists(processRoot);
                } catch (java.nio.file.DirectoryNotEmptyException ignored) {
                    // Other project runtime snapshots are still active.
                }
            }
        }

        private static void deleteTree(Path path) throws IOException {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            try (Stream<Path> paths = Files.walk(path)) {
                for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(item);
                }
            }
        }
    }

    public static final class RuntimeLease implements AutoCloseable {

        private final CachedProjectRuntime runtime;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RuntimeLease(CachedProjectRuntime runtime) {
            this.runtime = runtime;
        }

        public CachedProjectRuntime runtime() {
            return runtime;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    runtime.release();
                } catch (RuntimeException exception) {
                    LOGGER.warn("Failed to close retired project runtime after its last request", exception);
                }
            }
        }
    }

    public static final class CachedProjectRuntime implements AutoCloseable {

        private final ProjectContext project;
        private final ObjectMapper objectMapper;
        private final URLClassLoader classLoader;
        private final RuntimeSnapshot snapshot;
        private final RuntimeKey key;
        private final List<String> profiles;
        private final ConcurrentHashMap<String, Optional<Map<String, Object>>> appMetadata = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Map<String, Object>> configValues = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<ApiHandlerKey, Optional<ProjectApiHandler>> apiHandlers = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Optional<ProjectControllerHook>> controllerHooks = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Optional<ProjectRouteHandler>> routeHandlers = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Optional<ProjectSocketController>> socketControllers = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Optional<ProjectModelFactory>> modelFactories = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Optional<ProjectConstructor>> constructors = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<AutoCloseable> closeHooks = new CopyOnWriteArrayList<>();
        private volatile List<RouteDefinition> routeDefinitions;
        private int activeLeases;
        private boolean retired;
        private boolean closed;

        private CachedProjectRuntime(ProjectContext project, ObjectMapper objectMapper, URLClassLoader classLoader, RuntimeSnapshot snapshot, RuntimeKey key, List<String> profiles) {
            this.project = project;
            this.objectMapper = objectMapper;
            this.classLoader = classLoader;
            this.snapshot = snapshot;
            this.key = key;
            this.profiles = profiles;
        }

        public ClassLoader classLoader() {
            return classLoader;
        }

        RuntimeKey key() {
            return key;
        }

        public void onClose(AutoCloseable closeHook) {
            if (closeHook != null) {
                closeHooks.add(closeHook);
            }
        }

        private synchronized RuntimeLease acquire() {
            if (retired || closed) {
                throw new IllegalStateException("Project runtime is no longer available: " + key.projectName());
            }
            activeLeases++;
            return new RuntimeLease(this);
        }

        private void release() {
            boolean closeNow;
            synchronized (this) {
                if (activeLeases == 0) {
                    return;
                }
                activeLeases--;
                closeNow = retired && activeLeases == 0 && !closed;
                if (closeNow) {
                    closed = true;
                }
            }
            if (closeNow) {
                closeResources();
            }
        }

        public Optional<Map<String, Object>> appMetadata(String appId) {
            return appMetadata.computeIfAbsent(appId, this::readAppMetadata);
        }

        public Map<String, Object> configValues(String name) {
            return configValues.computeIfAbsent(name, this::readConfigValues);
        }

        public List<RouteDefinition> routeDefinitions() {
            List<RouteDefinition> definitions = routeDefinitions;
            if (definitions == null) {
                synchronized (this) {
                    definitions = routeDefinitions;
                    if (definitions == null) {
                        definitions = readRouteDefinitions();
                        routeDefinitions = definitions;
                    }
                }
            }
            return definitions;
        }

        public Optional<ProjectApiHandler> apiHandler(String handlerClass, String function) {
            return apiHandlers.computeIfAbsent(new ApiHandlerKey(handlerClass, function), key -> loadApiHandler(key.handlerClass(), key.function()));
        }

        public Optional<ProjectControllerHook> controllerHook(String controllerClass) {
            return controllerHooks.computeIfAbsent(controllerClass, this::loadControllerHook);
        }

        public Optional<ProjectRouteHandler> routeHandler(String handlerClass) {
            return routeHandlers.computeIfAbsent(handlerClass, this::loadRouteHandler);
        }

        public Optional<ProjectSocketController> socketController(String handlerClass) {
            return socketControllers.computeIfAbsent(handlerClass, this::loadSocketController);
        }

        public Optional<ProjectModelFactory> modelFactory(String namespace, List<String> classCandidates) {
            return modelFactories.computeIfAbsent(namespace, ignored -> loadModelFactory(classCandidates));
        }

        public Optional<ProjectConstructor> constructor(String className, Class<?> requiredType, Class<?> preferredArgumentType) {
            String key = className + "|" + requiredType.getName() + "|" + preferredArgumentType.getName();
            return constructors.computeIfAbsent(key, ignored -> loadConstructor(className, requiredType, preferredArgumentType));
        }

        @Override
        public void close() {
            boolean closeNow;
            synchronized (this) {
                retired = true;
                closeNow = activeLeases == 0 && !closed;
                if (closeNow) {
                    closed = true;
                }
            }
            if (closeNow) {
                closeResources();
            }
        }

        private void closeResources() {
            RuntimeException failure = null;
            for (AutoCloseable closeHook : closeHooks.reversed()) {
                try {
                    closeHook.close();
                } catch (Exception exception) {
                    RuntimeException wrapped = exception instanceof RuntimeException runtimeException
                            ? runtimeException
                            : new IllegalStateException("Failed to close project runtime resource", exception);
                    if (failure == null) {
                        failure = wrapped;
                    } else {
                        failure.addSuppressed(wrapped);
                    }
                }
            }
            closeHooks.clear();
            try {
                classLoader.close();
            } catch (IOException exception) {
                IllegalStateException wrapped = new IllegalStateException("Failed to close project runtime classloader", exception);
                if (failure == null) {
                    failure = wrapped;
                } else {
                    failure.addSuppressed(wrapped);
                }
            }
            try {
                snapshot.close();
            } catch (IOException exception) {
                IllegalStateException wrapped = new IllegalStateException("Failed to delete project runtime snapshot", exception);
                if (failure == null) {
                    failure = wrapped;
                } else {
                    failure.addSuppressed(wrapped);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private Optional<Map<String, Object>> readAppMetadata(String appId) {
            try {
                Path appRoot = project.bundleRoot().resolve("src/app");
                Path appJson = new SafePath(appRoot).resolveExisting(appId + "/app.json");
                Map<String, Object> metadata = objectMapper.readValue(Files.readAllBytes(appJson), new TypeReference<LinkedHashMap<String, Object>>() {
                });
                return Optional.of(Collections.unmodifiableMap(new LinkedHashMap<>(metadata)));
            } catch (IllegalArgumentException | IOException exception) {
                return Optional.empty();
            }
        }

        private Map<String, Object> readConfigValues(String name) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(ProjectConfigLoader.read(project, objectMapper, name, profiles)));
        }

        private List<RouteDefinition> readRouteDefinitions() {
            Path routeRoot = project.bundleRoot().resolve("src/route");
            if (!Files.isDirectory(routeRoot)) {
                return List.of();
            }
            try (Stream<Path> children = Files.list(routeRoot)) {
                return children
                        .filter(Files::isDirectory)
                        .map(this::routeDefinition)
                        .flatMap(Optional::stream)
                        .sorted(Comparator.comparingInt((RouteDefinition definition) -> definition.route().length()).reversed())
                        .toList();
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to scan WIZ route metadata", exception);
            }
        }

        private Optional<RouteDefinition> routeDefinition(Path routeDirectory) {
            try {
                Path appJson = new SafePath(routeDirectory).resolveExisting("app.json");
                Map<String, Object> metadata = objectMapper.readValue(Files.readAllBytes(appJson), new TypeReference<LinkedHashMap<String, Object>>() {
                });
                String directoryId = routeDirectory.getFileName().toString();
                String id = routeId(directoryId, string(metadata, "id", directoryId));
                String route = string(metadata, "route", string(metadata, "title", ""));
                if (route.isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(new RouteDefinition(
                        id,
                        string(metadata, "title", route),
                        route,
                        string(metadata, "controller", ControllerChain.DEFAULT_CONTROLLER_NAME),
                        methods(metadata),
                        handlerClass(id, metadata)));
            } catch (IllegalArgumentException | IOException exception) {
                return Optional.empty();
            }
        }

        private String handlerClass(String routeId, Map<String, Object> metadata) {
            String configured = string(metadata, "handler", "");
            if (!configured.isBlank()) {
                return ProjectJavaNaming.modernizeProjectPackage(project, configured);
            }
            return ProjectJavaNaming.routeHandlerClass(project, routeId);
        }

        private String routeId(String directoryId, String metadataId) {
            if (directoryId.startsWith("portal.") && !metadataId.startsWith("portal.")) {
                return directoryId;
            }
            return metadataId;
        }

        private List<String> methods(Map<String, Object> metadata) {
            Object value = metadata.get("methods");
            if (value == null) {
                value = metadata.get("method");
            }
            if (value instanceof Iterable<?> values) {
                ArrayList<String> methods = new ArrayList<>();
                values.forEach(item -> addMethod(methods, item));
                return List.copyOf(methods);
            }
            if (value == null || value.toString().isBlank()) {
                return List.of();
            }
            ArrayList<String> methods = new ArrayList<>();
            for (String method : value.toString().split(",")) {
                addMethod(methods, method);
            }
            return List.copyOf(methods);
        }

        private void addMethod(List<String> methods, Object method) {
            if (method != null && !method.toString().isBlank()) {
                methods.add(method.toString().trim().toUpperCase(Locale.ROOT));
            }
        }

        private String string(Map<String, Object> metadata, String key, String defaultValue) {
            Object value = metadata.get(key);
            return value == null || value.toString().isBlank() ? defaultValue : value.toString();
        }

        private Optional<ProjectApiHandler> loadApiHandler(String handlerClass, String function) {
            try {
                Class<?> handlerType = loadClass(handlerClass);
                Constructor<?> constructor = defaultConstructor(handlerType);
                Method method = findApiMethod(handlerType, function);
                if (method == null) {
                    return Optional.empty();
                }
                method.setAccessible(true);
                return Optional.of(new ProjectApiHandler(constructor, method));
            } catch (ClassNotFoundException exception) {
                throw new ProjectClassNotFoundException(handlerClass, exception);
            } catch (ReflectiveOperationException exception) {
                throw new ProjectReflectionException("Failed to inspect project API handler: " + handlerClass, exception);
            }
        }

        private Optional<ProjectControllerHook> loadControllerHook(String controllerClass) {
            try {
                Class<?> controllerType = loadClass(controllerClass);
                Constructor<?> constructor = defaultConstructor(controllerType);
                if (ControllerHook.class.isAssignableFrom(controllerType)) {
                    return Optional.of(new ProjectControllerHook(constructor, null, true));
                }
                Method before = findBeforeMethod(controllerType);
                if (before == null) {
                    return Optional.empty();
                }
                before.setAccessible(true);
                return Optional.of(new ProjectControllerHook(constructor, before, false));
            } catch (ClassNotFoundException exception) {
                return Optional.empty();
            } catch (ReflectiveOperationException exception) {
                throw new ProjectReflectionException("Failed to inspect project controller hook: " + controllerClass, exception);
            }
        }

        private Optional<ProjectRouteHandler> loadRouteHandler(String handlerClass) {
            try {
                Class<?> handlerType = loadClass(handlerClass);
                Constructor<?> constructor = defaultConstructor(handlerType);
                if (RouteHandler.class.isAssignableFrom(handlerType)) {
                    return Optional.of(new ProjectRouteHandler(constructor, null, true));
                }
                Method handle = findHandleMethod(handlerType);
                if (handle == null) {
                    return Optional.empty();
                }
                handle.setAccessible(true);
                return Optional.of(new ProjectRouteHandler(constructor, handle, false));
            } catch (ClassNotFoundException exception) {
                return Optional.empty();
            } catch (ReflectiveOperationException exception) {
                throw new ProjectReflectionException("Failed to inspect project route handler: " + handlerClass, exception);
            }
        }

        private Optional<ProjectSocketController> loadSocketController(String handlerClass) {
            try {
                Class<?> handlerType = loadClass(handlerClass);
                if (!SocketController.class.isAssignableFrom(handlerType)) {
                    throw new ProjectTypeMismatchException("socket handler does not implement SocketController");
                }
                return Optional.of(new ProjectSocketController(defaultConstructor(handlerType)));
            } catch (ClassNotFoundException exception) {
                return Optional.empty();
            } catch (ReflectiveOperationException exception) {
                throw new ProjectReflectionException("Failed to inspect project socket handler: " + handlerClass, exception);
            }
        }

        private Optional<ProjectModelFactory> loadModelFactory(List<String> classCandidates) {
            for (String className : classCandidates) {
                try {
                    Class<?> modelType = loadClass(className);
                    Constructor<?> constructor = findConstructor(modelType, WizContext.class)
                            .orElseGet(() -> defaultConstructorUnchecked(modelType));
                    return Optional.of(new ProjectModelFactory(constructor, constructor.getParameterCount() == 1));
                } catch (ClassNotFoundException exception) {
                    // Try the next convention candidate.
                }
            }
            return Optional.empty();
        }

        private Optional<ProjectConstructor> loadConstructor(String className, Class<?> requiredType, Class<?> preferredArgumentType) {
            try {
                Class<?> candidate = loadClass(className);
                if (!requiredType.isAssignableFrom(candidate)) {
                    return Optional.empty();
                }
                Constructor<?> constructor = findConstructor(candidate, preferredArgumentType)
                        .orElseGet(() -> defaultConstructorUnchecked(candidate));
                return Optional.of(new ProjectConstructor(constructor, constructor.getParameterCount() == 1));
            } catch (ClassNotFoundException exception) {
                return Optional.empty();
            }
        }

        private Class<?> loadClass(String className) throws ClassNotFoundException {
            return Class.forName(className, true, classLoader);
        }

        private Constructor<?> defaultConstructor(Class<?> type) throws NoSuchMethodException {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor;
        }

        private Constructor<?> defaultConstructorUnchecked(Class<?> type) {
            try {
                return defaultConstructor(type);
            } catch (NoSuchMethodException exception) {
                throw new ProjectReflectionException("Project class has no supported constructor: " + type.getName(), exception);
            }
        }

        private Optional<Constructor<?>> findConstructor(Class<?> type, Class<?> argumentType) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (constructor.getParameterCount() == 1 && constructor.getParameterTypes()[0].isAssignableFrom(argumentType)) {
                    constructor.setAccessible(true);
                    return Optional.of(constructor);
                }
            }
            return Optional.empty();
        }

        private Method findApiMethod(Class<?> handlerType, String function) {
            for (Method method : handlerType.getMethods()) {
                if (method.getName().equals(function)
                        && (method.getParameterCount() == 0
                                || (method.getParameterCount() == 1 && method.getParameterTypes()[0].isAssignableFrom(WizContext.class)))) {
                    return method;
                }
            }
            return null;
        }

        private Method findBeforeMethod(Class<?> controllerType) {
            for (Method method : controllerType.getMethods()) {
                if (method.getName().equals("before")
                        && (method.getParameterCount() == 0
                                || (method.getParameterCount() == 1 && method.getParameterTypes()[0].isAssignableFrom(WizContext.class)))) {
                    return method;
                }
            }
            return null;
        }

        private Method findHandleMethod(Class<?> handlerType) {
            for (Method method : handlerType.getMethods()) {
                if (method.getName().equals("handle")
                        && method.getParameterCount() == 2
                        && method.getParameterTypes()[0].isAssignableFrom(WizContext.class)
                        && method.getParameterTypes()[1].isAssignableFrom(WizSegment.class)) {
                    return method;
                }
            }
            return null;
        }
    }

    public record ProjectApiHandler(Constructor<?> constructor, Method method) {
    }

    public record ProjectControllerHook(Constructor<?> constructor, Method beforeMethod, boolean implementsControllerHook) {
    }

    public record ProjectRouteHandler(Constructor<?> constructor, Method handleMethod, boolean implementsRouteHandler) {
    }

    public record ProjectSocketController(Constructor<?> constructor) {
    }

    public record ProjectModelFactory(Constructor<?> constructor, boolean contextConstructor) {
    }

    public record ProjectConstructor(Constructor<?> constructor, boolean argumentConstructor) {
    }

    public static class ProjectClassNotFoundException extends RuntimeException {

        public ProjectClassNotFoundException(String className, Throwable cause) {
            super(className, cause);
        }
    }

    public static class ProjectReflectionException extends RuntimeException {

        public ProjectReflectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class ProjectTypeMismatchException extends RuntimeException {

        public ProjectTypeMismatchException(String message) {
            super(message);
        }
    }
}
