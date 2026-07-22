package com.wiz.build;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/** Resolves the Maven executable used for workspace dependency builds. */
public final class MavenExecutableResolver {

    private MavenExecutableResolver() {
    }

    public static Path require(Path workspaceRoot) {
        return require(workspaceRoot, System.getenv("PATH"));
    }

    static Path require(Path workspaceRoot, String pathValue) {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("WIZ workspace root is required to resolve Maven");
        }
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path wrapper = root.resolve(isWindows() ? "mvnw.cmd" : "mvnw");
        if (Files.exists(wrapper, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(wrapper)) {
                throw new IllegalStateException("Workspace Maven Wrapper is not a regular file: " + wrapper);
            }
            if (!isWindows() && !Files.isExecutable(wrapper)) {
                throw new IllegalStateException("Workspace Maven Wrapper is not executable: " + wrapper
                        + ". Run 'chmod +x mvnw' and retry.");
            }
            return wrapper;
        }

        return findOnPath(isWindows() ? "mvn.cmd" : "mvn", pathValue)
                .orElseThrow(() -> new IllegalStateException("Maven is required for this build but no executable was found. "
                        + "Add an executable Maven Wrapper at " + root.resolve("mvnw")
                        + " or install Maven and make 'mvn' available on PATH."));
    }

    private static Optional<Path> findOnPath(String command, String pathValue) {
        if (pathValue == null || pathValue.isBlank()) {
            return Optional.empty();
        }
        for (String entry : pathValue.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve(command).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean isWindows() {
        return File.separatorChar == '\\';
    }
}
