package com.wiz.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

final class ProjectConfigLoader {

    private ProjectConfigLoader() {
    }

    static Map<String, Object> read(ProjectContext project, ObjectMapper objectMapper, String name) {
        return read(project, objectMapper, name, List.of());
    }

    static Map<String, Object> read(ProjectContext project, ObjectMapper objectMapper, String name, List<String> profiles) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Path path : configFiles(project, name, profiles)) {
            values.putAll(readConfigFile(objectMapper, name, path));
        }
        return normalizeKeys(values);
    }

    static List<Path> configRoots(ProjectContext project) {
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
        return List.copyOf(roots);
    }

    private static List<Path> configFiles(ProjectContext project, String name, List<String> profiles) {
        java.util.ArrayList<Path> files = new java.util.ArrayList<>();
        for (Path configRoot : configRoots(project)) {
            SafePath safePath = new SafePath(configRoot);
            addFirstExisting(files, safePath, name, List.of(".yml", ".yaml", ".json"));
            if ("application".equals(name)) {
                for (String profile : profiles == null ? List.<String>of() : profiles) {
                    String normalizedProfile = normalizeProfile(profile);
                    if (normalizedProfile != null) {
                        addFirstExisting(files, safePath, name + "-" + normalizedProfile, List.of(".yml", ".yaml"));
                    }
                }
            }
        }
        return List.copyOf(files);
    }

    private static void addFirstExisting(List<Path> files, SafePath safePath, String name, List<String> extensions) {
        for (String extension : extensions) {
            Path candidate = safePath.resolve(name + extension);
            if (Files.isRegularFile(candidate)) {
                files.add(candidate);
                return;
            }
        }
    }

    private static String normalizeProfile(String profile) {
        if (profile == null || profile.isBlank()) {
            return null;
        }
        String normalized = profile.trim();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("Invalid Spring profile for project config: " + profile);
        }
        return normalized;
    }

    private static Map<String, Object> readConfigFile(ObjectMapper objectMapper, String name, Path path) {
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

    private static Map<String, Object> readYaml(Path path) {
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

    private static Map<String, Object> normalizeKeys(Map<String, Object> values) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> normalized.put(normalizeKey(key), value));
        return normalized;
    }

    private static String normalizeKey(String key) {
        return switch (key) {
            case "auth-base-uri", "auth_base_uri" -> "auth_baseuri";
            default -> key;
        };
    }
}
