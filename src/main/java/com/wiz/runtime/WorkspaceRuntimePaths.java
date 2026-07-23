package com.wiz.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;

/** Resolves WIZ process state outside the user-visible workspace structure. */
public final class WorkspaceRuntimePaths {

    public static final String RUNTIME_DIRECTORY_ENV = "WIZ_SPRING_RUNTIME_DIR";
    public static final String CACHE_DIRECTORY_ENV = "WIZ_SPRING_CACHE_DIR";
    public static final String STATE_DIRECTORY_ENV = "WIZ_SPRING_STATE_DIR";

    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final String HOST_KEY = hostKey();

    private WorkspaceRuntimePaths() {
    }

    public static Path buildLock(Path workspaceRoot) {
        return runtimeWorkspaceRoot(workspaceRoot).resolve("build.lock");
    }

    public static Path runtimeSnapshots(Path workspaceRoot) {
        return cacheRuntimeSnapshotsRoot(cacheWorkspaceRoot(workspaceRoot));
    }

    public static Path compilerClasspathCache(Path workspaceRoot) {
        return cacheWorkspaceRoot(workspaceRoot).resolve("compiler-classpath/v1");
    }

    public static Path mcpState(Path workspaceRoot) {
        return stateWorkspaceRoot(workspaceRoot).resolve("mcp-state.json");
    }

    public static Path prepareBuildLock(Path workspaceRoot) throws IOException {
        return prepareWorkspaceRoot(runtimeBase(), workspaceRoot).resolve("build.lock");
    }

    public static Path prepareRuntimeSnapshots(Path workspaceRoot) throws IOException {
        Path workspace = prepareWorkspaceRoot(cacheBase(), workspaceRoot);
        Path snapshots = ensurePrivateDirectory(workspace.resolve("runtime-snapshots"));
        return ensurePrivateDirectory(snapshots.resolve(HOST_KEY));
    }

    public static Path prepareCompilerClasspathCache(Path workspaceRoot) throws IOException {
        Path workspace = prepareWorkspaceRoot(cacheBase(), workspaceRoot);
        Path compilerClasspath = ensurePrivateDirectory(workspace.resolve("compiler-classpath"));
        return ensurePrivateDirectory(compilerClasspath.resolve("v1"));
    }

    public static Path prepareMcpState(Path workspaceRoot) throws IOException {
        return prepareWorkspaceRoot(stateBase(), workspaceRoot).resolve("mcp-state.json");
    }

    public static void secureFile(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("WIZ private state file is missing or is not a regular file: " + file);
        }
        setPosixPermissions(file, PRIVATE_FILE_PERMISSIONS);
    }

    public static void requireOutsideWorkspace(Path workspaceRoot, Path candidate) throws IOException {
        Path workspace = canonicalCandidate(workspaceRoot);
        Path external = canonicalCandidate(candidate);
        if (external.startsWith(workspace)) {
            throw new IOException("WIZ runtime, cache, and state paths must be outside the workspace: " + candidate);
        }
    }

    static String workspaceKey(Path workspaceRoot) {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("WIZ workspace root is required");
        }
        Path root = workspaceRoot.toAbsolutePath().normalize();
        try {
            if (Files.exists(root)) {
                root = root.toRealPath();
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest(root.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(value, 0, 16);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Failed to resolve WIZ workspace root: " + root, exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static Path runtimeWorkspaceRoot(Path workspaceRoot) {
        return externalWorkspaceRoot(runtimeBase(), workspaceRoot);
    }

    private static Path cacheWorkspaceRoot(Path workspaceRoot) {
        return externalWorkspaceRoot(cacheBase(), workspaceRoot);
    }

    private static Path stateWorkspaceRoot(Path workspaceRoot) {
        return externalWorkspaceRoot(stateBase(), workspaceRoot);
    }

    static Path cacheWorkspacesRoot() {
        return cacheBase().resolve("workspaces");
    }

    static Path prepareCacheWorkspacesRoot() throws IOException {
        Path base = ensurePrivateDirectory(cacheBase());
        return ensurePrivateDirectory(base.resolve("workspaces"));
    }

    static Path cacheRuntimeSnapshotsRoot(Path workspaceStore) {
        return workspaceStore.resolve("runtime-snapshots").resolve(HOST_KEY);
    }

    private static Path runtimeBase() {
        Path configured = environmentDirectory(RUNTIME_DIRECTORY_ENV);
        if (configured != null) {
            return configured;
        }
        Path home = userHome();
        if (home != null) {
            return home.resolve(".local/state/wiz-spring/runtime");
        }
        return temporaryBase("wiz-spring-runtime");
    }

    private static Path cacheBase() {
        Path configured = environmentDirectory(CACHE_DIRECTORY_ENV);
        if (configured != null) {
            return configured;
        }
        Path home = userHome();
        if (home != null) {
            return home.resolve(".cache/wiz-spring");
        }
        return temporaryBase("wiz-spring-cache");
    }

    private static Path stateBase() {
        Path configured = environmentDirectory(STATE_DIRECTORY_ENV);
        if (configured != null) {
            return configured;
        }
        Path xdgState = environmentDirectory("XDG_STATE_HOME");
        if (xdgState != null) {
            return xdgState.resolve("wiz-spring");
        }
        Path home = userHome();
        if (home != null) {
            return home.resolve(".local/state/wiz-spring");
        }
        return temporaryBase("wiz-spring-state");
    }

    private static Path environmentDirectory(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        Path path = Path.of(value.trim());
        if (!path.isAbsolute()) {
            throw new IllegalStateException(name + " must be an absolute directory: " + value.trim());
        }
        return path.normalize();
    }

    private static Path userHome() {
        String value = System.getProperty("user.home");
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }

    private static Path temporaryBase(String name) {
        String temporaryDirectory = System.getProperty("java.io.tmpdir");
        if (temporaryDirectory == null || temporaryDirectory.isBlank()) {
            throw new IllegalStateException("Java temporary directory is unavailable");
        }
        String user = System.getProperty("user.name", "unknown");
        return Path.of(temporaryDirectory, name + "-" + shortHash(user)).toAbsolutePath().normalize();
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String hostKey() {
        String machine = readIdentity(Path.of("/etc/machine-id"));
        if (machine == null) {
            machine = readIdentity(Path.of("/var/lib/dbus/machine-id"));
        }
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception ignored) {
                host = System.getProperty("os.name", "unknown-host");
            }
        }
        return "host-" + shortHash(host.trim() + "|" + (machine == null ? "unknown-machine" : machine));
    }

    private static String readIdentity(Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            String value = Files.readString(path).trim();
            return value.isBlank() ? null : value;
        } catch (IOException ignored) {
            return null;
        }
    }

    static Path ensurePrivateDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("WIZ private state path is not a directory or is a symbolic link: " + directory);
        }
        verifyCurrentUserOwns(directory);
        setPosixPermissions(directory, PRIVATE_DIRECTORY_PERMISSIONS);
        return directory;
    }

    private static Path prepareWorkspaceRoot(Path base, Path workspaceRoot) throws IOException {
        Path target = base.resolve("workspaces").resolve(workspaceKey(workspaceRoot));
        requireOutsideWorkspace(workspaceRoot, target);
        Path securedBase = ensurePrivateDirectory(base);
        Path workspaces = ensurePrivateDirectory(securedBase.resolve("workspaces"));
        return ensurePrivateDirectory(workspaces.resolve(workspaceKey(workspaceRoot)));
    }

    private static Path externalWorkspaceRoot(Path base, Path workspaceRoot) {
        Path target = base.resolve("workspaces").resolve(workspaceKey(workspaceRoot));
        try {
            requireOutsideWorkspace(workspaceRoot, target);
            return target;
        } catch (IOException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private static Path canonicalCandidate(Path path) throws IOException {
        if (path == null) {
            throw new IOException("Path is required for WIZ external state validation");
        }
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
    }

    private static void setPosixPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, permissions);
        }
    }

    private static void verifyCurrentUserOwns(Path path) throws IOException {
        String userName = System.getProperty("user.name");
        if (userName == null || userName.isBlank()) {
            throw new IOException("Current operating-system user is unavailable for WIZ private state validation");
        }
        UserPrincipal expected = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(userName);
        UserPrincipal actual = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        if (!expected.equals(actual)) {
            throw new IOException("WIZ private state directory must be owned by the current user: " + path);
        }
    }
}
