package com.wiz.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.springframework.core.io.FileSystemResource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public class ConfigService {

    private final ProjectContext project;
    private final ObjectMapper objectMapper;

    public ConfigService(ProjectContext project) {
        this(project, new ObjectMapper());
    }

    public ConfigService(ProjectContext project, ObjectMapper objectMapper) {
        this.project = project;
        this.objectMapper = objectMapper;
    }

    public ConfigNamespace namespace(String name) {
        return namespace(name, Map.of());
    }

    public ConfigNamespace namespace(String name, Map<String, Object> defaults) {
        validateName(name);
        Map<String, Object> fileValues = readConfigFile(name);
        Map<String, Object> normalizedValues = normalizeKeys(fileValues);
        Map<String, Object> values = new LinkedHashMap<>();
        defaults.forEach((key, value) -> values.put(key, defaultValue(value)));
        validateKeys(name, defaults, normalizedValues);
        values.putAll(normalizedValues);
        return new ConfigNamespace(name, values);
    }

    public <T> T get(String name, Class<T> type) {
        return get(name, type, Map.of());
    }

    public <T> T get(String name, Class<T> type, Map<String, Object> defaults) {
        return objectMapper.convertValue(namespace(name, defaults).values(), type);
    }

    private Map<String, Object> readConfigFile(String name) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Path path : configFiles(name)) {
            values.putAll(readConfigFile(name, path));
        }
        return values;
    }

    private Map<String, Object> readConfigFile(String name, Path path) {
        try {
            String filename = path.getFileName().toString();
            if (filename.endsWith(".json")) {
                return objectMapper.readValue(Files.readAllBytes(path), new TypeReference<>() {
                });
            }
            return readYaml(path);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Invalid project config: " + name, exception);
        }
    }

    private java.util.List<Path> configFiles(String name) {
        java.util.ArrayList<Path> files = new java.util.ArrayList<>();
        for (Path configRoot : configRoots()) {
            SafePath safePath = new SafePath(configRoot);
            for (String extension : java.util.List.of(".yml", ".yaml", ".json")) {
                Path candidate = safePath.resolve(name + extension);
                if (Files.isRegularFile(candidate)) {
                    files.add(candidate);
                    break;
                }
            }
        }
        return java.util.List.copyOf(files);
    }

    private java.util.List<Path> configRoots() {
        java.util.ArrayList<Path> roots = new java.util.ArrayList<>();
        Path parent = project.root().getParent();
        Path workspaceConfig = parent != null
                && parent.getFileName() != null
                && parent.getFileName().toString().equals("project")
                && parent.getParent() != null
                        ? parent.getParent().resolve("config")
                        : null;
        if (workspaceConfig != null && Files.isDirectory(workspaceConfig)) {
            roots.add(workspaceConfig);
        }
        roots.add(project.configRoot());
        Path bundleConfig = project.bundleRoot().resolve("config");
        if (Files.isDirectory(bundleConfig)) {
            roots.add(bundleConfig);
        }
        return java.util.List.copyOf(roots);
    }

    private Map<String, Object> readYaml(Path path) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new FileSystemResource(path));
        Properties properties = factory.getObject();
        if (properties == null) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        properties.forEach((key, value) -> values.put(key.toString(), value));
        return values;
    }

    private Map<String, Object> normalizeKeys(Map<String, Object> values) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> normalized.put(normalizeKey(key), value));
        return normalized;
    }

    private String normalizeKey(String key) {
        return switch (key) {
            case "auth-base-uri", "auth_base_uri" -> "auth_baseuri";
            default -> key;
        };
    }

    private void validateName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid config namespace");
        }
    }

    private void validateKeys(String name, Map<String, Object> defaults, Map<String, Object> fileValues) {
        if (defaults.isEmpty()) {
            return;
        }
        for (String key : fileValues.keySet()) {
            if (!defaults.containsKey(key)) {
                throw new IllegalArgumentException("Unknown config key in " + name + ": " + key);
            }
        }
    }

    private Object defaultValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>(map);
        }
        return value;
    }
}
