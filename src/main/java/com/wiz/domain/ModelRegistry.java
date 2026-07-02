package com.wiz.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.wiz.runtime.ProjectRuntimeCache;
import com.wiz.runtime.ProjectRuntimeCache.ProjectModelFactory;
import com.wiz.runtime.WizContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ModelRegistry {

    private final Map<String, ModelProvider<?>> providers = new LinkedHashMap<>();
    private final Map<String, Object> singletonInstances = new ConcurrentHashMap<>();
    private final ProjectRuntimeCache runtimeCache;

    public ModelRegistry() {
        this(List.of(), new ProjectRuntimeCache());
    }

    public ModelRegistry(ProjectRuntimeCache runtimeCache) {
        this(List.of(), runtimeCache);
    }

    public ModelRegistry(List<ModelProvider<?>> providers) {
        this(providers, new ProjectRuntimeCache());
    }

    @Autowired
    public ModelRegistry(List<ModelProvider<?>> providers, ProjectRuntimeCache runtimeCache) {
        this.runtimeCache = runtimeCache == null ? new ProjectRuntimeCache() : runtimeCache;
        providers.forEach(provider -> this.providers.put(provider.namespace(), provider));
    }

    public <T> T get(WizContext context, String namespace, Class<T> type) {
        validateNamespace(namespace);
        Object value = value(context, namespace);
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("Model namespace " + namespace + " is not a " + type.getName());
        }
        return type.cast(value);
    }

    private Object value(WizContext context, String namespace) {
        ModelProvider<?> provider = providers.get(namespace);
        if (provider != null) {
            return switch (provider.lifecycle()) {
                case SINGLETON -> singletonInstances.computeIfAbsent(namespace, ignored -> provider.create(context));
                case FACTORY -> provider.create(context);
                case REQUEST -> context.modelRegistry().computeIfAbsent(namespace, ignored -> provider.create(context));
            };
        }
        return context.modelRegistry().computeIfAbsent(namespace, ignored -> instantiateProjectModel(context, namespace));
    }

    private Object instantiateProjectModel(WizContext context, String namespace) {
        ProjectRuntimeCache.CachedProjectRuntime runtime = runtimeCache.get(context.project());
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(runtime.classLoader());
        try {
            ProjectModelFactory factory = runtime.modelFactory(namespace, classCandidates(context.project().name(), namespace))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown model namespace: " + namespace
                            + ". Add a Java model under src/model or src/portal/{portal}/model; examples: struct, struct/user, db/user, portal/post/struct."));
            try {
                return factory.contextConstructor()
                        ? factory.constructor().newInstance(context)
                        : factory.constructor().newInstance();
            } catch (ReflectiveOperationException exception) {
                throw new IllegalArgumentException("Failed to create model namespace: " + namespace, exception);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }

    private List<String> classCandidates(String projectName, String namespace) {
        String root = "com.wiz.project." + javaPackageSegment(projectName);
        if (namespace.equals("struct")) {
            return List.of(root + ".model.Struct");
        }
        if (namespace.startsWith("struct/")) {
            String name = namespace.substring("struct/".length());
            return List.of(root + ".model.struct." + className(name) + "Struct", root + ".model.struct." + className(name));
        }
        if (namespace.startsWith("db/")) {
            String name = namespace.substring("db/".length());
            return List.of(root + ".model.db." + className(name) + "Entity", root + ".model.db." + className(name));
        }
        if (namespace.startsWith("portal/")) {
            return portalClassCandidates(root, namespace);
        }
        return List.of(root + ".model." + className(namespace));
    }

    private List<String> portalClassCandidates(String root, String namespace) {
        String[] parts = namespace.split("/");
        if (parts.length < 3) {
            return List.of();
        }
        String portalRoot = root + ".portal." + javaPackageSegment(parts[1]) + ".model";
        if (parts[2].equals("struct") && parts.length == 3) {
            return List.of(portalRoot + "." + className(parts[1]) + "Struct", portalRoot + ".Struct");
        }
        if (parts[2].equals("struct") && parts.length == 4) {
            return List.of(
                    portalRoot + ".struct." + className(parts[3]) + "Service",
                    portalRoot + ".struct." + className(parts[3]) + "Struct",
                    portalRoot + ".struct." + className(parts[3]));
        }
        if (parts[2].equals("db") && parts.length == 4) {
            return List.of(portalRoot + ".db." + className(parts[3]) + "Entity", portalRoot + ".db." + className(parts[3]));
        }
        return List.of(portalRoot + "." + className(parts[parts.length - 1]));
    }

    private void validateNamespace(String namespace) {
        if (namespace == null || !namespace.matches("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*")) {
            throw new IllegalArgumentException("Invalid model namespace");
        }
    }

    private String className(String value) {
        StringBuilder builder = new StringBuilder();
        for (String part : value.split("[./_-]")) {
            if (!part.isBlank()) {
                builder.append(part.substring(0, 1).toUpperCase(java.util.Locale.ROOT)).append(part.substring(1));
            }
        }
        return builder.toString();
    }

    private String javaPackageSegment(String value) {
        String segment = value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (segment.isBlank() || Character.isDigit(segment.charAt(0))) {
            return "p_" + segment;
        }
        return segment;
    }
}
