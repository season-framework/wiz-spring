package com.wiz.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipProjectSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsZipProjectAndSkipsGitDirectory() throws Exception {
        Path zip = tempDir.resolve("project.wizproject");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            writeEntry(output, "src/app/page.local/app.json", "{}\n");
            writeEntry(output, ".git/config", "ignored\n");
        }
        Path target = tempDir.resolve("target");

        new ZipProjectSource().extract(zip, target);

        assertTrue(Files.exists(target.resolve("src/app/page.local/app.json")));
        assertFalse(Files.exists(target.resolve(".git/config")));
    }

    @Test
    void rejectsZipSlipAndAbsoluteEntries() throws Exception {
        Path zipSlip = tempDir.resolve("slip.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zipSlip))) {
            writeEntry(output, "../outside.txt", "bad\n");
        }

        assertThrows(IllegalArgumentException.class, () -> new ZipProjectSource().extract(zipSlip, tempDir.resolve("target-slip")));

        Path absolute = tempDir.resolve("absolute.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(absolute))) {
            writeEntry(output, "/tmp/outside.txt", "bad\n");
        }

        assertThrows(IllegalArgumentException.class, () -> new ZipProjectSource().extract(absolute, tempDir.resolve("target-absolute")));
    }

    @Test
    void rejectsSymlinkEntriesAndEntryCap() throws Exception {
        Path symlinkZip = tempDir.resolve("symlink.zip");
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(symlinkZip)) {
            ZipArchiveEntry entry = new ZipArchiveEntry("src/assets/link.txt");
            entry.setUnixMode(UnixStat.LINK_FLAG | 0777);
            output.putArchiveEntry(entry);
            output.write("/etc/passwd".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeArchiveEntry();
        }

        assertThrows(IllegalArgumentException.class, () -> new ZipProjectSource().extract(symlinkZip, tempDir.resolve("target-symlink")));

        Path capped = tempDir.resolve("capped.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(capped))) {
            writeEntry(output, "one.txt", "1");
            writeEntry(output, "two.txt", "2");
        }

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ZipProjectSource(1, 1024, 2048).extract(capped, tempDir.resolve("target-capped")));
        assertEquals("Zip project has too many entries", exception.getMessage());
    }

    private void writeEntry(ZipOutputStream output, String name, String content) throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.closeEntry();
    }
}