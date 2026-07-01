package com.wiz.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

public final class EmbeddedWorkspace {

    public static final String PROPERTIES_RESOURCE = "wiz/embedded-workspace.properties";
    public static final String FILES_RESOURCE = "wiz/embedded-workspace.files";
    public static final String FILE_RESOURCE_PREFIX = "wiz/embedded-workspace/";

    private EmbeddedWorkspace() {
    }

    public static Optional<Launch> extractIfPresent() throws IOException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Properties properties = new Properties();
        try (InputStream input = loader.getResourceAsStream(PROPERTIES_RESOURCE)) {
            if (input == null) {
                return Optional.empty();
            }
            properties.load(input);
        }

        String id = required(properties, "id");
        String project = properties.getProperty("project", ProjectRegistry.DEFAULT_PROJECT_NAME);
        Path root = cacheRoot().resolve(safeCacheName(id)).toAbsolutePath().normalize();
        Path marker = root.resolve(".wiz-spring-embedded-id");
        if (!Files.isRegularFile(marker) || !Files.readString(marker).strip().equals(id)) {
            delete(root);
            Files.createDirectories(root);
            extractFiles(loader, root);
            Files.writeString(marker, id + System.lineSeparator());
        }
        return Optional.of(new Launch(root, project));
    }

    private static void extractFiles(ClassLoader loader, Path root) throws IOException {
        List<String> files;
        try (InputStream input = loader.getResourceAsStream(FILES_RESOURCE)) {
            if (input == null) {
                throw new IOException("Embedded WIZ workspace file list is missing");
            }
            files = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
        }
        for (String file : files) {
            Path relative = Path.of(file);
            if (relative.isAbsolute() || !relative.normalize().equals(relative) || file.contains("\\")) {
                throw new IOException("Unsafe embedded WIZ workspace entry: " + file);
            }
            Path target = root.resolve(relative.toString()).normalize();
            if (!target.startsWith(root)) {
                throw new IOException("Embedded WIZ workspace entry escapes root: " + file);
            }
            try (InputStream input = loader.getResourceAsStream(FILE_RESOURCE_PREFIX + file)) {
                if (input == null) {
                    throw new IOException("Embedded WIZ workspace file is missing: " + file);
                }
                Files.createDirectories(target.getParent());
                Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static Path cacheRoot() {
        String configured = System.getProperty("wiz.embedded.cache-dir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            return Path.of(home, ".wiz-spring", "embedded");
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "wiz-spring", "embedded");
    }

    private static String safeCacheName(String id) {
        if (!id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid embedded WIZ workspace id");
        }
        return id;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Embedded WIZ workspace property is missing: " + key);
        }
        return value;
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    public record Launch(Path root, String project) {
    }
}
