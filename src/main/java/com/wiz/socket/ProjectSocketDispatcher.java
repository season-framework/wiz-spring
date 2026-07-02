package com.wiz.socket;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.wiz.core.ProjectJavaNaming;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.ProjectRuntimeCache;
import com.wiz.runtime.ProjectRuntimeCache.ProjectReflectionException;
import com.wiz.runtime.ProjectRuntimeCache.ProjectSocketController;
import com.wiz.runtime.ProjectRuntimeCache.ProjectTypeMismatchException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

@Service
public class ProjectSocketDispatcher {

    private final PathService paths;
    private final SocketRoomRegistry rooms;
    private final ProjectRuntimeCache runtimeCache;

    @Autowired
    public ProjectSocketDispatcher(PathService paths, SocketRoomRegistry rooms, ProjectRuntimeCache runtimeCache) {
        this.paths = paths;
        this.rooms = rooms;
        this.runtimeCache = runtimeCache == null ? new ProjectRuntimeCache() : runtimeCache;
    }

    public ProjectSocketDispatcher(PathService paths, SocketRoomRegistry rooms) {
        this(paths, rooms, new ProjectRuntimeCache());
    }

    ProjectSocketDispatcher(PathService paths, SocketRoomRegistry rooms, ObjectMapper objectMapper) {
        this(paths, rooms, new ProjectRuntimeCache(objectMapper));
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
        return runtimeCache.get(project).appMetadata(appId);
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
        ProjectRuntimeCache.CachedProjectRuntime projectRuntime = runtimeCache.get(project);
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(projectRuntime.classLoader());
        try {
            ProjectSocketController socketController = projectRuntime.socketController(handlerClass).orElse(null);
            if (socketController == null) {
                return new SocketEventResult(false, event, "socket handler not found");
            }
            SocketController controller = (SocketController) socketController.constructor().newInstance();
            SocketEventHandler eventHandler = controller.handlers().get(event);
            if (eventHandler == null) {
                return new SocketEventResult(false, event, "socket event handler not found");
            }
            return eventHandler.handle(session, payload == null ? Map.of() : payload, rooms);
        } catch (ProjectTypeMismatchException exception) {
            return new SocketEventResult(false, event, exception.getMessage());
        } catch (ReflectiveOperationException | ProjectReflectionException exception) {
            return new SocketEventResult(false, event, "socket dispatch failed");
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
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
