package com.wiz.build;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.wiz.runtime.EmbeddedWorkspace;
import com.wiz.runtime.ProjectContext;

public class StandaloneProjectJarService {

    private static final String ZIP_RESOURCE_ROOT = "/BOOT-INF/classes/" + EmbeddedWorkspace.FILE_RESOURCE_PREFIX;
    private static final String ZIP_PROPERTIES = "/BOOT-INF/classes/" + EmbeddedWorkspace.PROPERTIES_RESOURCE;
    private static final String ZIP_FILES = "/BOOT-INF/classes/" + EmbeddedWorkspace.FILES_RESOURCE;

    public Path packageJar(Path workspaceRoot, ProjectContext project, Path runtimeJar, Path output) throws IOException {
        Path runtime = runtimeJar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(runtime)) {
            throw new IllegalArgumentException("Runtime jar does not exist: " + runtime);
        }
        if (!Files.isDirectory(project.bundleRoot())) {
            throw new IllegalArgumentException("App bundle does not exist. Run build first.");
        }

        Path target = outputPath(project, output).toAbsolutePath().normalize();
        if (target.equals(runtime)) {
            throw new IllegalArgumentException("Output jar must be different from runtime jar");
        }
        Files.createDirectories(target.getParent());
        Files.copy(runtime, target, StandardCopyOption.REPLACE_EXISTING);

        List<EmbeddedFile> files = embeddedFiles(workspaceRoot, project);
        String id = "wiz-app-" + digest(files);
        writeEmbeddedWorkspace(target, id, files);
        writeChecksum(target);
        return target;
    }

    public Path checksumPath(Path jar) {
        Path absolute = jar.toAbsolutePath().normalize();
        return absolute.resolveSibling(absolute.getFileName() + ".sha256");
    }

    private Path outputPath(ProjectContext project, Path output) {
        Path target = output == null ? project.root().resolve("target").resolve("wiz-app.jar") : output;
        if (Files.isDirectory(target)) {
            return target.resolve("wiz-app.jar");
        }
        return target;
    }

    private List<EmbeddedFile> embeddedFiles(Path workspaceRoot, ProjectContext project) throws IOException {
        ArrayList<EmbeddedFile> files = new ArrayList<>();
        collectDirectory(workspaceRoot.resolve("config"), "config", files);
        collectDirectory(project.bundleRoot(), "bundle", files);
        files.sort(Comparator.comparing(EmbeddedFile::relativeName));
        return List.copyOf(files);
    }

    private void collectDirectory(Path sourceRoot, String targetPrefix, List<EmbeddedFile> files) throws IOException {
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            for (Path item : paths.sorted().toList()) {
                Path relative = sourceRoot.relativize(item);
                if (Files.isSymbolicLink(item)) {
                    throw new IllegalArgumentException("Symbolic links are not allowed in standalone jars: " + relative);
                }
                if (!Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String relativeName = targetPrefix + "/" + relative.toString().replace('\\', '/');
                if (relativeName.contains("../") || relativeName.startsWith("/")) {
                    throw new IllegalArgumentException("Standalone jar entry escapes target root: " + relativeName);
                }
                files.add(new EmbeddedFile(item, relativeName));
            }
        }
    }

    private String digest(List<EmbeddedFile> files) throws IOException {
        MessageDigest digest = sha256();
        for (EmbeddedFile file : files) {
            digest.update(file.relativeName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(file.source()));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
    }

    private void writeChecksum(Path jar) throws IOException {
        String digest = fileDigest(jar);
        Files.writeString(checksumPath(jar), digest + "  " + jar.getFileName() + "\n");
    }

    private String fileDigest(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void writeEmbeddedWorkspace(Path jar, String id, List<EmbeddedFile> files) throws IOException {
        URI uri = URI.create("jar:" + jar.toUri());
        try (FileSystem zip = FileSystems.newFileSystem(uri, Map.of())) {
            writeString(zip, ZIP_PROPERTIES, ""
                    + "id=" + id + "\n"
                    + "createdAt=" + Instant.now() + "\n");
            writeString(zip, ZIP_FILES, files.stream()
                    .map(EmbeddedFile::relativeName)
                    .collect(java.util.stream.Collectors.joining("\n", "", "\n")));
            for (EmbeddedFile file : files) {
                Path target = zip.getPath(ZIP_RESOURCE_ROOT + file.relativeName());
                Files.createDirectories(target.getParent());
                Files.copy(file.source(), target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void writeString(FileSystem zip, String path, String value) throws IOException {
        Path target = zip.getPath(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, value);
    }

    private record EmbeddedFile(Path source, String relativeName) {
    }
}
