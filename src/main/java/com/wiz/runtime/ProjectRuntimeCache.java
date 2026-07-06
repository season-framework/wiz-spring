package com.wiz.runtime;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.wiz.core.ProjectJavaNaming;
import com.wiz.dispatch.ControllerChain;
import com.wiz.dispatch.ControllerHook;
import com.wiz.dispatch.RouteDefinition;
import com.wiz.dispatch.RouteHandler;
import com.wiz.socket.SocketController;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProjectRuntimeCache implements AutoCloseable {

    private final ConcurrentHashMap<RuntimeKey, CachedProjectRuntime> runtimes = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final String profile;
    private final ClassLoaderFactory classLoaderFactory;

    @Autowired
    public ProjectRuntimeCache(Environment environment) {
        this(new ObjectMapper(), activeProfile(environment), URLClassLoader::new);
    }

    public ProjectRuntimeCache() {
        this(new ObjectMapper(), "default", URLClassLoader::new);
    }

    public ProjectRuntimeCache(ObjectMapper objectMapper) {
        this(objectMapper, "default", URLClassLoader::new);
    }

    ProjectRuntimeCache(ObjectMapper objectMapper, String profile, ClassLoaderFactory classLoaderFactory) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.profile = profile == null || profile.isBlank() ? "default" : profile;
        this.classLoaderFactory = classLoaderFactory == null ? URLClassLoader::new : classLoaderFactory;
    }

    public CachedProjectRuntime get(ProjectContext project) {
        RuntimeIdentity identity = identity(project);
        RuntimeKey key = runtimeKey(project, identity);
        synchronized (runtimes) {
            evictStaleProjectEntries(key);
            CachedProjectRuntime runtime = runtimes.get(key);
            if (runtime != null) {
                return runtime;
            }
            CachedProjectRuntime created = createRuntime(project, key);
            runtimes.put(key, created);
            return created;
        }
    }

    public void invalidate(ProjectContext project) {
        RuntimeIdentity identity = identity(project);
        synchronized (runtimes) {
            evict(identity);
        }
    }

    @PreDestroy
    @Override
    public void close() {
        synchronized (runtimes) {
            List<CachedProjectRuntime> values = List.copyOf(runtimes.values());
            runtimes.clear();
            values.forEach(CachedProjectRuntime::close);
        }
    }

    private CachedProjectRuntime createRuntime(ProjectContext project, RuntimeKey key) {
        try {
            ClassLoader parent = Thread.currentThread().getContextClassLoader();
            if (parent == null) {
                parent = ProjectRuntimeCache.class.getClassLoader();
            }
            URL[] urls = ProjectClassPath.apiUrls(project);
            URLClassLoader classLoader = classLoaderFactory.create(urls, parent);
            return new CachedProjectRuntime(project, objectMapper, classLoader, key);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create project runtime cache: " + key.projectName(), exception);
        }
    }

    private void evictStaleProjectEntries(RuntimeKey currentKey) {
        evict(currentKey.identity(), currentKey);
    }

    private void evict(RuntimeIdentity identity) {
        evict(identity, null);
    }

    private void evict(RuntimeIdentity identity, RuntimeKey keep) {
        List<RuntimeKey> stale = runtimes.keySet().stream()
                .filter(key -> key.identity().equals(identity))
                .filter(key -> keep == null || !key.equals(keep))
                .toList();
        for (RuntimeKey key : stale) {
            CachedProjectRuntime runtime = runtimes.remove(key);
            if (runtime != null) {
                runtime.close();
            }
        }
    }

    private RuntimeKey runtimeKey(ProjectContext project) {
        return runtimeKey(project, identity(project));
    }

    private RuntimeKey runtimeKey(ProjectContext project, RuntimeIdentity identity) {
        return new RuntimeKey(identity, project.name(), runtimeVersion(project));
    }

    private RuntimeIdentity identity(ProjectContext project) {
        return new RuntimeIdentity(workspaceRoot(project), project.root().toAbsolutePath().normalize(), project.name(), profile);
    }

    private Path workspaceRoot(ProjectContext project) {
        Path projectRoot = project.root().toAbsolutePath().normalize();
        Path projectsRoot = projectRoot.getParent();
        if (projectsRoot != null && projectsRoot.getFileName() != null && projectsRoot.getFileName().toString().equals("project")) {
            Path workspaceRoot = projectsRoot.getParent();
            if (workspaceRoot != null) {
                return workspaceRoot.toAbsolutePath().normalize();
            }
        }
        return projectRoot;
    }

    private String runtimeVersion(ProjectContext project) {
        Path marker = project.bundleRoot().resolve(BuildMarkerService.MARKER_FILE);
        if (Files.isRegularFile(marker)) {
            return "marker:" + modifiedTime(marker) + ":" + digest(marker);
        }
        String runtimeArtifactFingerprint = runtimeArtifactFingerprint(project);
        return "mtime:" + runtimeArtifactFingerprint + ":source:" + fingerprint(project.sourceRoot());
    }

    private String runtimeArtifactFingerprint(ProjectContext project) {
        return Stream.of(
                project.bundleRoot().resolve("classes"),
                project.bundleRoot().resolve("app-api.jar"),
                project.bundleRoot().resolve("lib"),
                project.bundleRoot().resolve("src/app"),
                project.bundleRoot().resolve("src/route"))
                .map(path -> path.getFileName() + "=" + fingerprint(path))
                .collect(Collectors.joining("|"));
    }

    private String fingerprint(Path path) {
        if (!Files.exists(path)) {
            return "missing";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateFingerprint(digest, path);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            return "mtime:" + maxModifiedTime(path);
        }
    }

    private void updateFingerprint(MessageDigest digest, Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            updateDigest(digest, path.getFileName().toString());
            updateDigest(digest, Long.toString(Files.size(path)));
            updateDigest(digest, Long.toString(modifiedTime(path)));
            return;
        }
        if (!Files.isDirectory(path)) {
            updateDigest(digest, "other");
            updateDigest(digest, Long.toString(modifiedTime(path)));
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path file : paths
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList()) {
                updateDigest(digest, path.relativize(file).toString().replace('\\', '/'));
                updateDigest(digest, Long.toString(Files.size(file)));
                updateDigest(digest, Long.toString(modifiedTime(file)));
            }
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private long maxModifiedTime(Path path) {
        if (!Files.exists(path)) {
            return 0L;
        }
        if (Files.isRegularFile(path)) {
            return modifiedTime(path);
        }
        if (!Files.isDirectory(path)) {
            return 0L;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            return paths.filter(Files::isRegularFile)
                    .mapToLong(this::modifiedTime)
                    .max()
                    .orElse(0L);
        } catch (IOException exception) {
            return modifiedTime(path);
        }
    }

    private long modifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return 0L;
        }
    }

    private String digest(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (IOException | NoSuchAlgorithmException exception) {
            return "unreadable";
        }
    }

    private static String activeProfile(Environment environment) {
        if (environment == null) {
            return "default";
        }
        String[] active = environment.getActiveProfiles();
        if (active.length == 0) {
            active = environment.getDefaultProfiles();
        }
        if (active.length == 0) {
            return "default";
        }
        return Arrays.stream(active).sorted().collect(Collectors.joining(","));
    }


    @FunctionalInterface
    interface ClassLoaderFactory {
        URLClassLoader create(URL[] urls, ClassLoader parent);
    }

    private record RuntimeIdentity(Path workspaceRoot, Path projectRoot, String projectName, String profile) {
    }

    private record RuntimeKey(RuntimeIdentity identity, String projectName, String version) {
    }

    private record ApiHandlerKey(String handlerClass, String function) {
    }

    public static final class CachedProjectRuntime implements AutoCloseable {

        private final ProjectContext project;
        private final ObjectMapper objectMapper;
        private final URLClassLoader classLoader;
        private final RuntimeKey key;
        private final ConcurrentHashMap<String, Optional<Map<String, Object>>> appMetadata = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Map<String, Object>> configValues = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<ApiHandlerKey, Optional<ProjectApiHandler>> apiHandlers = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Optional<ProjectControllerHook>> controllerHooks = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Optional<ProjectRouteHandler>> routeHandlers = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Optional<ProjectSocketController>> socketControllers = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Optional<ProjectModelFactory>> modelFactories = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Optional<ProjectConstructor>> constructors = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<AutoCloseable> closeHooks = new CopyOnWriteArrayList<>();
        private volatile List<RouteDefinition> routeDefinitions;

        private CachedProjectRuntime(ProjectContext project, ObjectMapper objectMapper, URLClassLoader classLoader, RuntimeKey key) {
            this.project = project;
            this.objectMapper = objectMapper;
            this.classLoader = classLoader;
            this.key = key;
        }

        public ClassLoader classLoader() {
            return classLoader;
        }

        RuntimeKey key() {
            return key;
        }

        public void onClose(AutoCloseable closeHook) {
            if (closeHook != null) {
                closeHooks.add(closeHook);
            }
        }

        public Optional<Map<String, Object>> appMetadata(String appId) {
            return appMetadata.computeIfAbsent(appId, this::readAppMetadata);
        }

        public Map<String, Object> configValues(String name) {
            return configValues.computeIfAbsent(name, this::readConfigValues);
        }

        public List<RouteDefinition> routeDefinitions() {
            List<RouteDefinition> definitions = routeDefinitions;
            if (definitions == null) {
                synchronized (this) {
                    definitions = routeDefinitions;
                    if (definitions == null) {
                        definitions = readRouteDefinitions();
                        routeDefinitions = definitions;
                    }
                }
            }
            return definitions;
        }

        public Optional<ProjectApiHandler> apiHandler(String handlerClass, String function) {
            return apiHandlers.computeIfAbsent(new ApiHandlerKey(handlerClass, function), key -> loadApiHandler(key.handlerClass(), key.function()));
        }

        public Optional<ProjectControllerHook> controllerHook(String controllerClass) {
            return controllerHooks.computeIfAbsent(controllerClass, this::loadControllerHook);
        }

        public Optional<ProjectRouteHandler> routeHandler(String handlerClass) {
            return routeHandlers.computeIfAbsent(handlerClass, this::loadRouteHandler);
        }

        public Optional<ProjectSocketController> socketController(String handlerClass) {
            return socketControllers.computeIfAbsent(handlerClass, this::loadSocketController);
        }

        public Optional<ProjectModelFactory> modelFactory(String namespace, List<String> classCandidates) {
            return modelFactories.computeIfAbsent(namespace, ignored -> loadModelFactory(classCandidates));
        }

        public Optional<ProjectConstructor> constructor(String className, Class<?> requiredType, Class<?> preferredArgumentType) {
            String key = className + "|" + requiredType.getName() + "|" + preferredArgumentType.getName();
            return constructors.computeIfAbsent(key, ignored -> loadConstructor(className, requiredType, preferredArgumentType));
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            for (AutoCloseable closeHook : closeHooks.reversed()) {
                try {
                    closeHook.close();
                } catch (Exception exception) {
                    RuntimeException wrapped = exception instanceof RuntimeException runtimeException
                            ? runtimeException
                            : new IllegalStateException("Failed to close project runtime resource", exception);
                    if (failure == null) {
                        failure = wrapped;
                    } else {
                        failure.addSuppressed(wrapped);
                    }
                }
            }
            closeHooks.clear();
            try {
                classLoader.close();
            } catch (IOException exception) {
                IllegalStateException wrapped = new IllegalStateException("Failed to close project runtime classloader", exception);
                if (failure == null) {
                    failure = wrapped;
                } else {
                    failure.addSuppressed(wrapped);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private Optional<Map<String, Object>> readAppMetadata(String appId) {
            try {
                Path appRoot = project.bundleRoot().resolve("src/app");
                Path appJson = new SafePath(appRoot).resolveExisting(appId + "/app.json");
                Map<String, Object> metadata = objectMapper.readValue(Files.readAllBytes(appJson), new TypeReference<LinkedHashMap<String, Object>>() {
                });
                return Optional.of(Collections.unmodifiableMap(new LinkedHashMap<>(metadata)));
            } catch (IllegalArgumentException | IOException exception) {
                return Optional.empty();
            }
        }

        private Map<String, Object> readConfigValues(String name) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(ProjectConfigLoader.read(project, objectMapper, name)));
        }

        private List<RouteDefinition> readRouteDefinitions() {
            Path routeRoot = project.bundleRoot().resolve("src/route");
            if (!Files.isDirectory(routeRoot)) {
                return List.of();
            }
            try (Stream<Path> children = Files.list(routeRoot)) {
                return children
                        .filter(Files::isDirectory)
                        .map(this::routeDefinition)
                        .flatMap(Optional::stream)
                        .sorted(Comparator.comparingInt((RouteDefinition definition) -> definition.route().length()).reversed())
                        .toList();
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to scan WIZ route metadata", exception);
            }
        }

        private Optional<RouteDefinition> routeDefinition(Path routeDirectory) {
            try {
                Path appJson = new SafePath(routeDirectory).resolveExisting("app.json");
                Map<String, Object> metadata = objectMapper.readValue(Files.readAllBytes(appJson), new TypeReference<LinkedHashMap<String, Object>>() {
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
                        handlerClass(id, metadata)));
            } catch (IllegalArgumentException | IOException exception) {
                return Optional.empty();
            }
        }

        private String handlerClass(String routeId, Map<String, Object> metadata) {
            String configured = string(metadata, "handler", "");
            if (!configured.isBlank()) {
                return configured;
            }
            return ProjectJavaNaming.routeHandlerClass(project, routeId);
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

        private Optional<ProjectApiHandler> loadApiHandler(String handlerClass, String function) {
            try {
                Class<?> handlerType = loadClass(handlerClass);
                Constructor<?> constructor = defaultConstructor(handlerType);
                Method method = findApiMethod(handlerType, function);
                if (method == null) {
                    return Optional.empty();
                }
                method.setAccessible(true);
                return Optional.of(new ProjectApiHandler(constructor, method));
            } catch (ClassNotFoundException exception) {
                throw new ProjectClassNotFoundException(handlerClass, exception);
            } catch (ReflectiveOperationException exception) {
                throw new ProjectReflectionException("Failed to inspect project API handler: " + handlerClass, exception);
            }
        }

        private Optional<ProjectControllerHook> loadControllerHook(String controllerClass) {
            try {
                Class<?> controllerType = loadClass(controllerClass);
                Constructor<?> constructor = defaultConstructor(controllerType);
                if (ControllerHook.class.isAssignableFrom(controllerType)) {
                    return Optional.of(new ProjectControllerHook(constructor, null, true));
                }
                Method before = findBeforeMethod(controllerType);
                if (before == null) {
                    return Optional.empty();
                }
                before.setAccessible(true);
                return Optional.of(new ProjectControllerHook(constructor, before, false));
            } catch (ClassNotFoundException exception) {
                return Optional.empty();
            } catch (ReflectiveOperationException exception) {
                throw new ProjectReflectionException("Failed to inspect project controller hook: " + controllerClass, exception);
            }
        }

        private Optional<ProjectRouteHandler> loadRouteHandler(String handlerClass) {
            try {
                Class<?> handlerType = loadClass(handlerClass);
                Constructor<?> constructor = defaultConstructor(handlerType);
                if (RouteHandler.class.isAssignableFrom(handlerType)) {
                    return Optional.of(new ProjectRouteHandler(constructor, null, true));
                }
                Method handle = findHandleMethod(handlerType);
                if (handle == null) {
                    return Optional.empty();
                }
                handle.setAccessible(true);
                return Optional.of(new ProjectRouteHandler(constructor, handle, false));
            } catch (ClassNotFoundException exception) {
                return Optional.empty();
            } catch (ReflectiveOperationException exception) {
                throw new ProjectReflectionException("Failed to inspect project route handler: " + handlerClass, exception);
            }
        }

        private Optional<ProjectSocketController> loadSocketController(String handlerClass) {
            try {
                Class<?> handlerType = loadClass(handlerClass);
                if (!SocketController.class.isAssignableFrom(handlerType)) {
                    throw new ProjectTypeMismatchException("socket handler does not implement SocketController");
                }
                return Optional.of(new ProjectSocketController(defaultConstructor(handlerType)));
            } catch (ClassNotFoundException exception) {
                return Optional.empty();
            } catch (ReflectiveOperationException exception) {
                throw new ProjectReflectionException("Failed to inspect project socket handler: " + handlerClass, exception);
            }
        }

        private Optional<ProjectModelFactory> loadModelFactory(List<String> classCandidates) {
            for (String className : classCandidates) {
                try {
                    Class<?> modelType = loadClass(className);
                    Constructor<?> constructor = findConstructor(modelType, WizContext.class)
                            .orElseGet(() -> defaultConstructorUnchecked(modelType));
                    return Optional.of(new ProjectModelFactory(constructor, constructor.getParameterCount() == 1));
                } catch (ClassNotFoundException exception) {
                    // Try the next convention candidate.
                }
            }
            return Optional.empty();
        }

        private Optional<ProjectConstructor> loadConstructor(String className, Class<?> requiredType, Class<?> preferredArgumentType) {
            try {
                Class<?> candidate = loadClass(className);
                if (!requiredType.isAssignableFrom(candidate)) {
                    return Optional.empty();
                }
                Constructor<?> constructor = findConstructor(candidate, preferredArgumentType)
                        .orElseGet(() -> defaultConstructorUnchecked(candidate));
                return Optional.of(new ProjectConstructor(constructor, constructor.getParameterCount() == 1));
            } catch (ClassNotFoundException exception) {
                return Optional.empty();
            }
        }

        private Class<?> loadClass(String className) throws ClassNotFoundException {
            return Class.forName(className, true, classLoader);
        }

        private Constructor<?> defaultConstructor(Class<?> type) throws NoSuchMethodException {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor;
        }

        private Constructor<?> defaultConstructorUnchecked(Class<?> type) {
            try {
                return defaultConstructor(type);
            } catch (NoSuchMethodException exception) {
                throw new ProjectReflectionException("Project class has no supported constructor: " + type.getName(), exception);
            }
        }

        private Optional<Constructor<?>> findConstructor(Class<?> type, Class<?> argumentType) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (constructor.getParameterCount() == 1 && constructor.getParameterTypes()[0].isAssignableFrom(argumentType)) {
                    constructor.setAccessible(true);
                    return Optional.of(constructor);
                }
            }
            return Optional.empty();
        }

        private Method findApiMethod(Class<?> handlerType, String function) {
            for (Method method : handlerType.getMethods()) {
                if (method.getName().equals(function)
                        && (method.getParameterCount() == 0
                                || (method.getParameterCount() == 1 && method.getParameterTypes()[0].isAssignableFrom(WizContext.class)))) {
                    return method;
                }
            }
            return null;
        }

        private Method findBeforeMethod(Class<?> controllerType) {
            for (Method method : controllerType.getMethods()) {
                if (method.getName().equals("before")
                        && (method.getParameterCount() == 0
                                || (method.getParameterCount() == 1 && method.getParameterTypes()[0].isAssignableFrom(WizContext.class)))) {
                    return method;
                }
            }
            return null;
        }

        private Method findHandleMethod(Class<?> handlerType) {
            for (Method method : handlerType.getMethods()) {
                if (method.getName().equals("handle")
                        && method.getParameterCount() == 2
                        && method.getParameterTypes()[0].isAssignableFrom(WizContext.class)
                        && method.getParameterTypes()[1].isAssignableFrom(WizSegment.class)) {
                    return method;
                }
            }
            return null;
        }
    }

    public record ProjectApiHandler(Constructor<?> constructor, Method method) {
    }

    public record ProjectControllerHook(Constructor<?> constructor, Method beforeMethod, boolean implementsControllerHook) {
    }

    public record ProjectRouteHandler(Constructor<?> constructor, Method handleMethod, boolean implementsRouteHandler) {
    }

    public record ProjectSocketController(Constructor<?> constructor) {
    }

    public record ProjectModelFactory(Constructor<?> constructor, boolean contextConstructor) {
    }

    public record ProjectConstructor(Constructor<?> constructor, boolean argumentConstructor) {
    }

    public static class ProjectClassNotFoundException extends RuntimeException {

        public ProjectClassNotFoundException(String className, Throwable cause) {
            super(className, cause);
        }
    }

    public static class ProjectReflectionException extends RuntimeException {

        public ProjectReflectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class ProjectTypeMismatchException extends RuntimeException {

        public ProjectTypeMismatchException(String message) {
            super(message);
        }
    }
}
