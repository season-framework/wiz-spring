package com.wiz.dispatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.wiz.core.ProjectJavaNaming;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.SafePath;

import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class RouteRegistry {

    private final ObjectMapper objectMapper;

    public RouteRegistry() {
        this(new ObjectMapper());
    }

    public RouteRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<RouteDefinition> definitions(ProjectContext project) {
        ArrayList<RouteDefinition> definitions = new ArrayList<>(bundleDefinitions(project));
        return definitions.stream()
                .sorted(Comparator.comparingInt((RouteDefinition definition) -> definition.route().length()).reversed())
                .toList();
    }

    private List<RouteDefinition> bundleDefinitions(ProjectContext project) {
        Path routeRoot = project.bundleRoot().resolve("src/route");
        if (!Files.isDirectory(routeRoot)) {
            return List.of();
        }

        try (Stream<Path> children = Files.list(routeRoot)) {
            return children
                    .filter(Files::isDirectory)
                    .map(routeDirectory -> definition(project, routeDirectory))
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan WIZ route metadata", exception);
        }
    }

    private Optional<RouteDefinition> definition(ProjectContext project, Path routeDirectory) {
        try {
            Path appJson = new SafePath(routeDirectory).resolveExisting("app.json");
            Map<String, Object> metadata = objectMapper.readValue(Files.readAllBytes(appJson), new TypeReference<>() {
            });
            String directoryId = routeDirectory.getFileName().toString();
            String id = routeId(directoryId, string(metadata, "id", directoryId));
            String route = string(metadata, "route", string(metadata, "title", ""));
            if (route.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new RouteDefinition(
                    id,
                    string(metadata, "title", route),
                    route,
                    string(metadata, "controller", ControllerChain.DEFAULT_CONTROLLER_NAME),
                    methods(metadata),
                    handlerClass(project.name(), id, metadata)));
        } catch (IllegalArgumentException | IOException exception) {
            return Optional.empty();
        }
    }

    private String handlerClass(String projectName, String routeId, Map<String, Object> metadata) {
        String configured = string(metadata, "handler", "");
        if (!configured.isBlank()) {
            return configured;
        }
        return ProjectJavaNaming.routeHandlerClass(projectName, routeId);
    }

    private String routeId(String directoryId, String metadataId) {
        if (directoryId.startsWith("portal.") && !metadataId.startsWith("portal.")) {
            return directoryId;
        }
        return metadataId;
    }

    private List<String> methods(Map<String, Object> metadata) {
        Object value = metadata.get("methods");
        if (value == null) {
            value = metadata.get("method");
        }
        if (value instanceof Iterable<?> values) {
            ArrayList<String> methods = new ArrayList<>();
            values.forEach(item -> addMethod(methods, item));
            return List.copyOf(methods);
        }
        if (value == null || value.toString().isBlank()) {
            return List.of();
        }
        ArrayList<String> methods = new ArrayList<>();
        for (String method : value.toString().split(",")) {
            addMethod(methods, method);
        }
        return List.copyOf(methods);
    }

    private void addMethod(List<String> methods, Object method) {
        if (method != null && !method.toString().isBlank()) {
            methods.add(method.toString().trim().toUpperCase(Locale.ROOT));
        }
    }

    private String string(Map<String, Object> metadata, String key, String defaultValue) {
        Object value = metadata.get(key);
        return value == null || value.toString().isBlank() ? defaultValue : value.toString();
    }

}
