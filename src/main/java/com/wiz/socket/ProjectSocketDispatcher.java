package com.wiz.socket;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.wiz.core.ProjectJavaNaming;
import com.wiz.config.WizRedirectProperties;
import com.wiz.dispatch.ControllerChain;
import com.wiz.domain.ModelRegistry;
import com.wiz.http.ResponseEnvelope;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectObservabilityRegistry;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.ProjectRuntimeCache;
import com.wiz.runtime.ProjectRuntimeCache.ProjectReflectionException;
import com.wiz.runtime.ProjectRuntimeCache.ProjectSocketController;
import com.wiz.runtime.ProjectRuntimeCache.ProjectTypeMismatchException;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizRequest;
import com.wiz.runtime.WizResponse;
import com.wiz.runtime.WizResult;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

@Service
public class ProjectSocketDispatcher {

    private final PathService paths;
    private final SocketRoomRegistry rooms;
    private final ProjectRuntimeCache runtimeCache;
    private final ControllerChain controllerChain;
    private final ModelRegistry modelRegistry;
    private final WizRedirectProperties redirectProperties;
    private final ProjectObservabilityRegistry observability;

    @Autowired
    public ProjectSocketDispatcher(PathService paths, SocketRoomRegistry rooms, ProjectRuntimeCache runtimeCache, ControllerChain controllerChain, ModelRegistry modelRegistry, WizRedirectProperties redirectProperties, ProjectObservabilityRegistry observability) {
        this.paths = paths;
        this.rooms = rooms;
        this.runtimeCache = runtimeCache == null ? new ProjectRuntimeCache() : runtimeCache;
        this.controllerChain = controllerChain == null ? new ControllerChain(this.runtimeCache) : controllerChain;
        this.modelRegistry = modelRegistry == null ? new ModelRegistry(this.runtimeCache) : modelRegistry;
        this.redirectProperties = redirectProperties == null ? new WizRedirectProperties() : redirectProperties;
        this.observability = observability == null ? new ProjectObservabilityRegistry() : observability;
    }

    public ProjectSocketDispatcher(PathService paths, SocketRoomRegistry rooms, ProjectRuntimeCache runtimeCache, ControllerChain controllerChain, ModelRegistry modelRegistry, WizRedirectProperties redirectProperties) {
        this(paths, rooms, runtimeCache, controllerChain, modelRegistry, redirectProperties, null);
    }

    public ProjectSocketDispatcher(PathService paths, SocketRoomRegistry rooms, ProjectRuntimeCache runtimeCache) {
        this(paths, rooms, runtimeCache, null, null, null);
    }

    public ProjectSocketDispatcher(PathService paths, SocketRoomRegistry rooms) {
        this(paths, rooms, new ProjectRuntimeCache());
    }

    ProjectSocketDispatcher(PathService paths, SocketRoomRegistry rooms, ObjectMapper objectMapper) {
        this(paths, rooms, new ProjectRuntimeCache(objectMapper));
    }

    public SocketEventResult dispatch(SocketSession session, String event, Map<String, Object> payload) {
        ProjectContext project = paths.workspaceContext();
        try (WizContext context = new WizContext(socketRequest(session), new WizResponse(), project, modelRegistry, redirectProperties, runtimeCache, observability)) {
            ProjectRuntimeCache.CachedProjectRuntime projectRuntime = context.projectRuntime();
            Optional<Map<String, Object>> metadata = projectRuntime.appMetadata(session.namespace().appId());
            Optional<SocketEventResult> controllerResult = authorize(context, event, metadata.orElse(Map.of()));
            if (controllerResult.isPresent()) {
                return controllerResult.get();
            }
            String handlerClass = metadata.flatMap(this::socketHandlerClass)
                    .orElseGet(() -> ProjectJavaNaming.appSocketHandlerClass(project, session.namespace().appId()));
            return dispatchProjectSocket(projectRuntime, session, handlerClass, event, payload);
        }
    }

    public SocketRoomRegistry rooms() {
        return rooms;
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

    private Optional<SocketEventResult> authorize(WizContext context, String event, Map<String, Object> metadata) {
        if ("disconnect".equals(event)) {
            return Optional.empty();
        }
        return controllerChain.before(context, metadata)
                .map(result -> new SocketEventResult(false, event, rejectionMessage(result)));
    }

    private WizRequest socketRequest(SocketSession session) {
        WizRequest.Builder builder = WizRequest.builder()
                .method("GET")
                .path(session.namespace().path())
                .remoteAddress(session.remoteAddress())
                .session(session.httpSession());
        session.cookies().forEach(builder::cookie);
        return builder.build();
    }

    private String rejectionMessage(WizResult result) {
        Object entity = result.entity();
        if (entity instanceof ResponseEnvelope envelope && envelope.data() instanceof Map<?, ?> data) {
            Object message = data.get("message");
            if (message != null && !message.toString().isBlank()) {
                return message.toString();
            }
            Object error = data.get("error");
            if (error != null && !error.toString().isBlank()) {
                return error.toString();
            }
        }
        return result.httpStatus() == 401 ? "unauthorized" : "socket request rejected";
    }

    private SocketEventResult dispatchProjectSocket(ProjectRuntimeCache.CachedProjectRuntime projectRuntime, SocketSession session, String handlerClass, String event, Map<String, Object> payload) {
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
