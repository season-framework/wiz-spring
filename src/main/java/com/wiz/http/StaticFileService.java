package com.wiz.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.ProjectRegistry;
import com.wiz.runtime.SafePath;

import org.springframework.stereotype.Service;

@Service
public class StaticFileService {

    private final ProjectRegistry projectRegistry;

    public StaticFileService(ProjectRegistry projectRegistry) {
        this.projectRegistry = projectRegistry;
    }

    public Optional<StaticFile> findAsset(String assetPath, Map<String, String> cookies) {
        return currentWorkspace(cookies).flatMap(project -> regularFile(project.bundleAssetsRoot(), assetPath, "public, max-age=3600"));
    }

    public Optional<StaticFile> findSpaFile(String requestPath, Map<String, String> cookies) {
        return currentWorkspace(cookies).flatMap(project -> {
            Path wwwRoot = project.bundleWwwRoot();
            String normalizedRequestPath = normalizeRequestPath(requestPath);
            Optional<StaticFile> requestedFile = regularFile(wwwRoot, normalizedRequestPath, "no-cache");
            if (requestedFile.isPresent()) {
                return requestedFile;
            }
            return regularFile(wwwRoot, "index.html", "no-cache");
        });
    }

    private Optional<ProjectContext> currentWorkspace(Map<String, String> cookies) {
        try {
            return Optional.of(projectRegistry.workspace());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return Optional.empty();
        }
    }

    private Optional<StaticFile> regularFile(Path basePath, String relativePath, String cacheControl) {
        try {
            SafePath safePath = new SafePath(basePath);
            Path path = safePath.resolveExisting(relativePath == null ? "" : relativePath);
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            return Optional.of(new StaticFile(path, mediaType(path), cacheControl));
        } catch (IllegalArgumentException | IOException exception) {
            return Optional.empty();
        }
    }

    private String normalizeRequestPath(String requestPath) {
        if (requestPath == null || requestPath.isBlank() || "/".equals(requestPath)) {
            return "index.html";
        }
        String normalized = requestPath;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.isBlank() ? "index.html" : normalized;
    }

    private String mediaType(Path path) throws IOException {
        String filename = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (filename.endsWith(".html")) {
            return "text/html; charset=UTF-8";
        }
        if (filename.endsWith(".js")) {
            return "text/javascript; charset=UTF-8";
        }
        if (filename.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }
        if (filename.endsWith(".json") || filename.endsWith(".map")) {
            return "application/json; charset=UTF-8";
        }
        if (filename.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (filename.endsWith(".png")) {
            return "image/png";
        }
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (filename.endsWith(".gif")) {
            return "image/gif";
        }
        if (filename.endsWith(".webp")) {
            return "image/webp";
        }
        if (filename.endsWith(".txt")) {
            return "text/plain; charset=UTF-8";
        }

        String probedType = Files.probeContentType(path);
        return probedType == null ? "application/octet-stream" : probedType;
    }

    public record StaticFile(Path path, String mediaType, String cacheControl) {
    }
}
