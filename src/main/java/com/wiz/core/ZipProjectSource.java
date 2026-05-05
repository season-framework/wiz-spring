package com.wiz.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

public class ZipProjectSource {

    private static final int DEFAULT_MAX_ENTRIES = 5000;
    private static final long DEFAULT_MAX_ENTRY_BYTES = 50L * 1024L * 1024L;
    private static final long DEFAULT_MAX_TOTAL_BYTES = 250L * 1024L * 1024L;

    private final int maxEntries;
    private final long maxEntryBytes;
    private final long maxTotalBytes;

    public ZipProjectSource() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_ENTRY_BYTES, DEFAULT_MAX_TOTAL_BYTES);
    }

    public ZipProjectSource(int maxEntries, long maxEntryBytes, long maxTotalBytes) {
        this.maxEntries = maxEntries;
        this.maxEntryBytes = maxEntryBytes;
        this.maxTotalBytes = maxTotalBytes;
    }

    public void extract(Path zipPath, Path targetRoot) throws IOException {
        Path source = zipPath.toAbsolutePath().normalize();
        Path target = targetRoot.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Zip project source must be a file: " + source);
        }
        Files.createDirectories(target);

        int entryCount = 0;
        long totalBytes = 0;
        try (ZipFile zipFile = new ZipFile(source.toFile())) {
            Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                entryCount++;
                if (entryCount > maxEntries) {
                    throw new IllegalArgumentException("Zip project has too many entries");
                }
                if (skipEntry(entry.getName())) {
                    continue;
                }
                Path destination = destination(target, entry);
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                long declaredSize = entry.getSize();
                if (declaredSize > maxEntryBytes) {
                    throw new IllegalArgumentException("Zip entry exceeds size limit: " + entry.getName());
                }
                Files.createDirectories(destination.getParent());
                try (InputStream input = zipFile.getInputStream(entry);
                        OutputStream output = Files.newOutputStream(destination)) {
                    long copied = copyBounded(input, output, entry.getName());
                    totalBytes += copied;
                    if (totalBytes > maxTotalBytes) {
                        throw new IllegalArgumentException("Zip project exceeds total size limit");
                    }
                }
            }
        }
    }

    private Path destination(Path targetRoot, ZipArchiveEntry entry) {
        String name = entry.getName();
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0 || name.contains("\\")) {
            throw new IllegalArgumentException("Unsupported zip entry name");
        }
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Absolute zip entries are not allowed: " + name);
        }
        if (entry.isUnixSymlink()) {
            throw new IllegalArgumentException("Symbolic links are not allowed in zip project sources: " + name);
        }
        Path destination = targetRoot.resolve(name).normalize();
        if (!destination.startsWith(targetRoot)) {
            throw new IllegalArgumentException("Zip project entry escapes target directory: " + name);
        }
        return destination;
    }

    private boolean skipEntry(String name) {
        if (name == null) {
            return true;
        }
        for (String part : name.split("/")) {
            if (part.equals(".git")) {
                return true;
            }
        }
        return false;
    }

    private long copyBounded(InputStream input, OutputStream output, String entryName) throws IOException {
        byte[] buffer = new byte[8192];
        long copied = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            copied += read;
            if (copied > maxEntryBytes) {
                throw new IllegalArgumentException("Zip entry exceeds size limit: " + entryName);
            }
            output.write(buffer, 0, read);
        }
        return copied;
    }
}