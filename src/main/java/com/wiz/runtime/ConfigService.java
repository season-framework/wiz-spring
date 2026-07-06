package com.wiz.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

public class ConfigService {

    private final ProjectContext project;
    private final ObjectMapper objectMapper;
    private final ProjectRuntimeCache.CachedProjectRuntime runtime;

    public ConfigService(ProjectContext project) {
        this(project, new ObjectMapper(), null);
    }

    public ConfigService(ProjectContext project, ObjectMapper objectMapper) {
        this(project, objectMapper, null);
    }

    public ConfigService(ProjectContext project, ProjectRuntimeCache.CachedProjectRuntime runtime) {
        this(project, new ObjectMapper(), runtime);
    }

    ConfigService(ProjectContext project, ObjectMapper objectMapper, ProjectRuntimeCache.CachedProjectRuntime runtime) {
        this.project = project;
        this.objectMapper = objectMapper;
        this.runtime = runtime;
    }

    public ConfigNamespace namespace(String name) {
        return namespace(name, Map.of());
    }

    public ConfigNamespace namespace(String name, Map<String, Object> defaults) {
        validateName(name);
        Map<String, Object> normalizedValues = runtime == null
                ? ProjectConfigLoader.read(project, objectMapper, name)
                : runtime.configValues(name);
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
