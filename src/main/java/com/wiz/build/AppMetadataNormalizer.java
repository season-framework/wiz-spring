package com.wiz.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.wiz.core.ProjectJavaNaming;
import com.wiz.runtime.ProjectContext;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

final class AppMetadataNormalizer {

    private final ObjectMapper objectMapper;

    AppMetadataNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void normalize(ProjectContext project, Path buildSourceRoot) throws IOException {
        normalizeApps(project, buildSourceRoot.resolve("app"));
        normalizeRoutes(project, buildSourceRoot.resolve("route"));
    }

    private void normalizeApps(ProjectContext project, Path appRoot) throws IOException {
        if (!Files.isDirectory(appRoot)) {
            return;
        }
        try (Stream<Path> apps = Files.list(appRoot)) {
            for (Path app : apps.filter(Files::isDirectory).toList()) {
                String appId = app.getFileName().toString();
                Path appJson = app.resolve("app.json");
                LinkedHashMap<String, Object> metadata = readMetadata(appJson);
                metadata.put("id", appId);
                putDefault(metadata, "mode", defaultMode(appId));
                metadata.put("controller", defaultController(appId, string(metadata, "controller")));
                putDefault(metadata, "viewuri", defaultViewUri(appId));
                putDefault(metadata, "path", "./" + appId + "/" + appId + ".component");
                String selector = ProjectJavaNaming.selector(appId);
                putDefault(metadata, "template", selector + "()");
                putDefault(metadata, "name", ProjectJavaNaming.componentName(appId));
                putNgBuild(metadata, appId);
                putNg(metadata, appId);
                String apiHandlerClass = nestedString(metadata, "api", "handler", ProjectJavaNaming.appApiHandlerClass(project.name(), appId));
                if (appJavaSourceExists(app, "api.java", apiHandlerClass)) {
                    putNestedDefault(metadata, "api", "handler", apiHandlerClass);
                }
                String socketHandlerClass = nestedString(metadata, "socket", "handler", ProjectJavaNaming.appSocketHandlerClass(project.name(), appId));
                if (appJavaSourceExists(app, "socket.java", socketHandlerClass)) {
                    putNestedDefault(metadata, "socket", "handler", socketHandlerClass);
                }
                writeMetadata(appJson, metadata);
            }
        }
    }

    private boolean appJavaSourceExists(Path app, String conventionalName, String handlerClass) {
        if (Files.isRegularFile(app.resolve(conventionalName))) {
            return true;
        }
        String handlerFileName = handlerClass.substring(handlerClass.lastIndexOf('.') + 1) + ".java";
        return Files.isRegularFile(app.resolve(handlerFileName));
    }

    private void normalizeRoutes(ProjectContext project, Path routeRoot) throws IOException {
        if (!Files.isDirectory(routeRoot)) {
            return;
        }
        try (Stream<Path> routes = Files.list(routeRoot)) {
            for (Path route : routes.filter(Files::isDirectory).toList()) {
                String routeId = route.getFileName().toString();
                Path appJson = route.resolve("app.json");
                LinkedHashMap<String, Object> metadata = readMetadata(appJson);
                metadata.put("id", routeId);
                String routePath = defaultRoutePath(routeId, metadata);
                putDefault(metadata, "route", routePath);
                putDefault(metadata, "path", routePath);
                putDefault(metadata, "title", routePath);
                metadata.put("controller", defaultController(routeId, string(metadata, "controller")));
                if (!metadata.containsKey("methods") && !metadata.containsKey("method")) {
                    metadata.put("methods", List.of());
                }
                putDefault(metadata, "handler", ProjectJavaNaming.routeHandlerClass(project.name(), routeId));
                writeMetadata(appJson, metadata);
            }
        }
    }

    private LinkedHashMap<String, Object> readMetadata(Path metadataFile) throws IOException {
        if (!Files.isRegularFile(metadataFile)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> metadata = objectMapper.readValue(Files.readAllBytes(metadataFile), new TypeReference<>() {
        });
        return new LinkedHashMap<>(metadata);
    }

    private void writeMetadata(Path metadataFile, LinkedHashMap<String, Object> metadata) throws IOException {
        Files.createDirectories(metadataFile.getParent());
        Files.writeString(metadataFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata) + "\n");
    }

    private void putNgBuild(LinkedHashMap<String, Object> metadata, String appId) {
        LinkedHashMap<String, Object> ngBuild = nestedMap(metadata, "ng.build");
        putDefault(ngBuild, "id", appId);
        putDefault(ngBuild, "name", ProjectJavaNaming.componentName(appId));
        putDefault(ngBuild, "path", "./" + appId + "/" + appId + ".component");
    }

    private void putNg(LinkedHashMap<String, Object> metadata, String appId) {
        LinkedHashMap<String, Object> ng = nestedMap(metadata, "ng");
        putDefault(ng, "selector", ProjectJavaNaming.selector(appId));
        ng.putIfAbsent("inputs", List.of());
        ng.putIfAbsent("outputs", List.of());
    }

    private void putNestedDefault(LinkedHashMap<String, Object> metadata, String key, String nestedKey, String defaultValue) {
        LinkedHashMap<String, Object> nested = nestedMap(metadata, key);
        putDefault(nested, nestedKey, defaultValue);
    }

    private LinkedHashMap<String, Object> nestedMap(LinkedHashMap<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
            map.forEach((nestedKey, nestedValue) -> nested.put(String.valueOf(nestedKey), nestedValue));
            metadata.put(key, nested);
            return nested;
        }
        LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
        metadata.put(key, nested);
        return nested;
    }

    private void putDefault(Map<String, Object> metadata, String key, String defaultValue) {
        Object value = metadata.get(key);
        if (value == null || value.toString().isBlank()) {
            metadata.put(key, defaultValue);
        }
    }

    private String defaultMode(String appId) {
        if (appId.startsWith("portal.")) {
            return "portal";
        }
        if (appId.startsWith("page.")) {
            return "page";
        }
        return "app";
    }

    private String defaultController(String id, String configured) {
        if (configured == null || configured.isBlank()) {
            return "base";
        }
        Optional<String> portalModule = portalModule(id);
        if (portalModule.isPresent() && !configured.contains("/") && !configured.startsWith("com.")) {
            return "portal/" + portalModule.get() + "/" + configured;
        }
        return configured;
    }

    private String defaultViewUri(String appId) {
        if (appId.startsWith("page.")) {
            return "/" + appId.substring("page.".length()).replace('.', '/');
        }
        return "";
    }

    private String defaultRoutePath(String routeId, Map<String, Object> metadata) {
        String configuredRoute = string(metadata, "route");
        if (!configuredRoute.isBlank()) {
            return configuredRoute;
        }
        String configuredPath = string(metadata, "path");
        if (!configuredPath.isBlank()) {
            return configuredPath;
        }
        return "/" + routeId.replace('.', '/');
    }

    private Optional<String> portalModule(String id) {
        if (!id.startsWith("portal.")) {
            return Optional.empty();
        }
        String[] parts = id.split("\\.");
        return parts.length >= 2 ? Optional.of(parts[1]) : Optional.empty();
    }

    private String string(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private String nestedString(Map<String, Object> metadata, String key, String nestedKey, String fallback) {
        Object value = metadata.get(key);
        if (value instanceof Map<?, ?> map) {
            Object nested = map.get(nestedKey);
            if (nested != null && !nested.toString().isBlank()) {
                return nested.toString().trim();
            }
        }
        return fallback;
    }
}
