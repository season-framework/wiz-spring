package com.wiz.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

public class SafePath {

    private final Path base;

    public SafePath(Path base) {
        if (base == null) {
            throw new IllegalArgumentException("Base path is required");
        }
        this.base = canonicalizeBase(base);
    }

    public Path base() {
        return base;
    }

    public Path resolve(String relativePath) {
        return resolve(Path.of(relativePath == null ? "" : relativePath));
    }

    public Path resolve(Path relativePath) {
        if (relativePath == null) {
            throw new IllegalArgumentException("Relative path is required");
        }
        if (relativePath.isAbsolute()) {
            throw new IllegalArgumentException("Absolute paths are not allowed");
        }
        Path candidate = base.resolve(relativePath).normalize();
        ensureInsideBase(candidate);
        return candidate;
    }

    public Path resolveExisting(String relativePath) throws IOException {
        Path candidate = resolve(relativePath);
        if (!Files.exists(candidate)) {
            throw new NoSuchFileException("Path does not exist: " + relativePath);
        }
        Path realPath = candidate.toRealPath();
        ensureInsideBase(realPath);
        return realPath;
    }

    public Path resolveForWrite(String relativePath) throws IOException {
        Path candidate = resolve(relativePath);
        ensureNearestExistingPathInsideBase(candidate);
        return candidate;
    }

    private Path canonicalizeBase(Path input) {
        Path normalized = input.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            return normalized;
        }
        try {
            return normalized.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Base path cannot be resolved", exception);
        }
    }

    private void ensureNearestExistingPathInsideBase(Path candidate) throws IOException {
        Path current = candidate;
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        if (current != null) {
            ensureInsideBase(current.toRealPath());
        }
    }

    private void ensureInsideBase(Path candidate) {
        if (!candidate.normalize().startsWith(base)) {
            throw new IllegalArgumentException("Path escapes base directory");
        }
    }
}