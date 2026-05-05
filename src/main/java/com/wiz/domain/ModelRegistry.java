package com.wiz.domain;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.wiz.runtime.WizContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ModelRegistry {

    private final Map<String, ModelProvider<?>> providers = new LinkedHashMap<>();
    private final Map<String, Object> singletonInstances = new ConcurrentHashMap<>();

    public ModelRegistry() {
        this(List.of());
    }

    @Autowired
    public ModelRegistry(List<ModelProvider<?>> providers) {
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
        for (String className : classCandidates(context.project().name(), namespace)) {
            try {
                Class<?> modelType = loadProjectClass(context, className);
                return instantiate(modelType, context);
            } catch (ClassNotFoundException exception) {
                // Try the next convention candidate.
            } catch (ReflectiveOperationException exception) {
                throw new IllegalArgumentException("Failed to create model namespace: " + namespace, exception);
            }
        }
        throw new IllegalArgumentException("Unknown model namespace: " + namespace
            + ". Add a Java model under src/model or src/portal/{portal}/model; examples: struct, struct/user, db/user, portal/post/struct.");
    }

    private Class<?> loadProjectClass(WizContext context, String className) throws ClassNotFoundException {
        ClassLoader currentLoader = Thread.currentThread().getContextClassLoader();
        try {
            return Class.forName(className, true, currentLoader);
        } catch (ClassNotFoundException exception) {
            URLClassLoader loader = projectClassLoader(context, currentLoader);
            context.onCleanup(() -> close(loader));
            return Class.forName(className, true, loader);
        }
    }

    private Object instantiate(Class<?> modelType, WizContext context) throws ReflectiveOperationException {
        Constructor<?> contextConstructor = findConstructor(modelType, WizContext.class);
        if (contextConstructor != null) {
            contextConstructor.setAccessible(true);
            return contextConstructor.newInstance(context);
        }
        Constructor<?> defaultConstructor = modelType.getDeclaredConstructor();
        defaultConstructor.setAccessible(true);
        return defaultConstructor.newInstance();
    }

    private Constructor<?> findConstructor(Class<?> type, Class<?> parameter) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 1 && constructor.getParameterTypes()[0].isAssignableFrom(parameter)) {
                return constructor;
            }
        }
        return null;
    }

    private URLClassLoader projectClassLoader(WizContext context, ClassLoader parent) {
        try {
            ArrayList<URL> urls = new ArrayList<>();
            Path classes = context.project().bundleRoot().resolve("classes");
            Path jar = context.project().bundleRoot().resolve("project-api.jar");
            if (Files.isDirectory(classes)) {
                urls.add(classes.toUri().toURL());
            }
            if (Files.isRegularFile(jar)) {
                urls.add(jar.toUri().toURL());
            }
            return new URLClassLoader(urls.toArray(URL[]::new), parent);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to create project model classloader", exception);
        }
    }

    private void close(URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to close project model classloader", exception);
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