package com.wiz.socket;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.wiz.core.ProjectJavaNaming;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.SafePath;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProjectSocketDispatcher {

    private final PathService paths;
    private final SocketRoomRegistry rooms;
    private final ObjectMapper objectMapper;

    @Autowired
    public ProjectSocketDispatcher(PathService paths, SocketRoomRegistry rooms) {
        this(paths, rooms, new ObjectMapper());
    }

    ProjectSocketDispatcher(PathService paths, SocketRoomRegistry rooms, ObjectMapper objectMapper) {
        this.paths = paths;
        this.rooms = rooms;
        this.objectMapper = objectMapper;
    }

    public SocketEventResult dispatch(SocketSession session, String event, Map<String, Object> payload) {
        ProjectContext project = paths.projectContext(session.namespace().project());
        Optional<Map<String, Object>> metadata = appMetadata(project, session.namespace().appId());
        String handlerClass = metadata.flatMap(this::socketHandlerClass)
                .orElseGet(() -> ProjectJavaNaming.appSocketHandlerClass(project.name(), session.namespace().appId()));
        return dispatchProjectSocket(project, session, handlerClass, event, payload);
    }

    public SocketRoomRegistry rooms() {
        return rooms;
    }

    private Optional<Map<String, Object>> appMetadata(ProjectContext project, String appId) {
        try {
            Path appRoot = project.bundleRoot().resolve("src/app");
            Path appJson = new SafePath(appRoot).resolveExisting(appId + "/app.json");
            Map<String, Object> metadata = objectMapper.readValue(Files.readAllBytes(appJson), new TypeReference<>() {
            });
            return Optional.of(metadata);
        } catch (IllegalArgumentException | IOException exception) {
            return Optional.empty();
        }
    }

    private Optional<String> socketHandlerClass(Map<String, Object> metadata) {
        Object socket = metadata.get("socket");
        if (!(socket instanceof Map<?, ?> socketMap)) {
            return Optional.empty();
        }
        Object handler = socketMap.get("handler");
        if (handler == null || handler.toString().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(handler.toString());
    }

    private SocketEventResult dispatchProjectSocket(ProjectContext project, SocketSession session, String handlerClass, String event, Map<String, Object> payload) {
        try (URLClassLoader loader = new URLClassLoader(projectApiUrls(project), Thread.currentThread().getContextClassLoader())) {
            ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                Class<?> handlerType = Class.forName(handlerClass, true, loader);
                Object handler = handlerType.getDeclaredConstructor().newInstance();
                if (!(handler instanceof SocketController controller)) {
                    return new SocketEventResult(false, event, "socket handler does not implement SocketController");
                }
                SocketEventHandler eventHandler = controller.handlers().get(event);
                if (eventHandler == null) {
                    return new SocketEventResult(false, event, "socket event handler not found");
                }
                return eventHandler.handle(session, payload == null ? Map.of() : payload, rooms);
            } finally {
                Thread.currentThread().setContextClassLoader(previousLoader);
            }
        } catch (ClassNotFoundException exception) {
            return new SocketEventResult(false, event, "socket handler not found");
        } catch (InvocationTargetException exception) {
            return new SocketEventResult(false, event, "socket event handler failed");
        } catch (ReflectiveOperationException | IOException exception) {
            return new SocketEventResult(false, event, "socket dispatch failed");
        }
    }

    private URL[] projectApiUrls(ProjectContext project) throws IOException {
        ArrayList<URL> urls = new ArrayList<>();
        Path classes = project.bundleRoot().resolve("classes");
        Path jar = project.bundleRoot().resolve("project-api.jar");
        if (Files.isDirectory(classes)) {
            urls.add(classes.toUri().toURL());
        }
        if (Files.isRegularFile(jar)) {
            urls.add(jar.toUri().toURL());
        }
        return urls.toArray(URL[]::new);
    }

    static Map<String, Object> payload(Object data) {
        if (data == null) {
            return Map.of();
        }
        if (data instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (key != null) {
                    payload.put(key.toString(), value);
                }
            });
            return payload;
        }
        return Map.of("value", data);
    }
}
