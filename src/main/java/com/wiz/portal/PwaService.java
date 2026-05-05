package com.wiz.portal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.SafePath;
import com.wiz.session.SeasonConfig;

public class PwaService {

    private final ProjectContext project;
    private final SeasonConfig config;

    public PwaService(ProjectContext project, SeasonConfig config) {
        this.project = project;
        this.config = config;
    }

    public String serviceWorkerScript() {
        return serviceWorkerFile()
                .map(this::readString)
                .orElse("");
    }

    public Map<String, Object> manifest() {
        return Map.of(
                "name", config.pwaTitle(),
                "short_name", config.pwaTitle(),
                "start_url", config.pwaStartUrl(),
                "display", config.pwaDisplay(),
                "background_color", config.pwaBackgroundColor(),
                "theme_color", config.pwaThemeColor(),
                "orientation", config.pwaOrientation(),
                "icons", List.of(
                        Map.of("src", config.pwaIcon192(), "sizes", "192x192", "type", "image/png"),
                        Map.of("src", config.pwaIcon512(), "sizes", "512x512", "type", "image/png")));
    }

    private Optional<Path> serviceWorkerFile() {
        for (Path root : List.of(project.bundleRoot().resolve("config"), project.configRoot())) {
            Optional<Path> candidate = regularFile(root, "pwa/sw.js");
            if (candidate.isPresent()) {
                return candidate;
            }
        }
        return Optional.empty();
    }

    private Optional<Path> regularFile(Path root, String relativePath) {
        try {
            Path path = new SafePath(root).resolveExisting(relativePath);
            return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
        } catch (IllegalArgumentException | IOException exception) {
            return Optional.empty();
        }
    }

    private String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read PWA service worker", exception);
        }
    }
}