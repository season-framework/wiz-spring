package com.wiz.dispatch;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Optional;

import com.wiz.core.ProjectJavaNaming;
import com.wiz.runtime.ProjectRuntimeCache;
import com.wiz.runtime.ProjectRuntimeCache.ProjectApiHandler;
import com.wiz.runtime.ProjectRuntimeCache.ProjectClassNotFoundException;
import com.wiz.runtime.ProjectRuntimeCache.ProjectReflectionException;
import com.wiz.runtime.WizBadRequestException;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizRequest;
import com.wiz.runtime.WizResult;
import com.wiz.runtime.WizRuntime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

@Service
public class AppApiDispatcher {

    private final WizRuntime runtime;
    private final ControllerChain controllerChain;
    private final ProjectRuntimeCache runtimeCache;

    @Autowired
    public AppApiDispatcher(WizRuntime runtime, ControllerChain controllerChain) {
        this(runtime, controllerChain, runtimeCache(runtime));
    }

    public AppApiDispatcher(WizRuntime runtime) {
        this(runtime, runtimeCache(runtime));
    }

    public AppApiDispatcher(WizRuntime runtime, ObjectMapper objectMapper) {
        this(runtime, runtimeCache(runtime));
    }

    public AppApiDispatcher(WizRuntime runtime, ObjectMapper objectMapper, ControllerChain controllerChain) {
        this(runtime, controllerChain, runtimeCache(runtime));
    }

    private AppApiDispatcher(WizRuntime runtime, ProjectRuntimeCache runtimeCache) {
        this(runtime, new ControllerChain(runtimeCache), runtimeCache);
    }

    private AppApiDispatcher(WizRuntime runtime, ControllerChain controllerChain, ProjectRuntimeCache runtimeCache) {
        this.runtime = runtime;
        this.controllerChain = controllerChain;
        this.runtimeCache = runtimeCache == null ? new ProjectRuntimeCache() : runtimeCache;
    }

    private static ProjectRuntimeCache runtimeCache(WizRuntime runtime) {
        return runtime == null ? new ProjectRuntimeCache() : runtime.runtimeCache();
    }

    public WizResult dispatch(WizRequest request, String appId, String function, String path) {
        try (WizContext context = runtime.createContext(request)) {
            Optional<Map<String, Object>> metadata = appMetadata(context, appId);
            if (metadata.isEmpty()) {
                return context.response().status(404, Map.of("error", "app api not found"));
            }
            Optional<WizResult> controllerResult = controllerChain.before(context, metadata.get());
            if (controllerResult.isPresent()) {
                return controllerResult.get();
            }
            Optional<String> handlerClass = metadata.flatMap(this::javaHandlerClass)
                    .or(() -> Optional.of(ProjectJavaNaming.appApiHandlerClass(context.project(), appId)));
            return dispatchProjectJavaApi(context, handlerClass.get(), function);
        }
    }

    private Optional<Map<String, Object>> appMetadata(WizContext context, String appId) {
        return runtimeCache.get(context.project()).appMetadata(appId);
    }

    private WizResult dispatchProjectJavaApi(WizContext context, String handlerClass, String function) {
        ProjectRuntimeCache.CachedProjectRuntime projectRuntime = runtimeCache.get(context.project());
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(projectRuntime.classLoader());
        try {
            ProjectApiHandler apiHandler = projectRuntime.apiHandler(handlerClass, function).orElse(null);
            if (apiHandler == null) {
                return context.response().status(404, Map.of("error", "function not found"));
            }
            Object handler = apiHandler.constructor().newInstance();
            Object value = apiHandler.method().getParameterCount() == 1
                    ? apiHandler.method().invoke(handler, context)
                    : apiHandler.method().invoke(handler);
            if (value instanceof WizResult result) {
                return result;
            }
            return context.response().ok(value);
        } catch (ProjectClassNotFoundException exception) {
            return context.response().status(500, Map.of("error", "java api handler not found"));
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof WizBadRequestException badRequest) {
                return context.response().status(400, badRequest.data());
            }
            return context.response().status(500, Map.of("error", "java api invocation failed"));
        } catch (ReflectiveOperationException | ProjectReflectionException exception) {
            return context.response().status(500, Map.of("error", "java api invocation failed"));
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }

    private Optional<String> javaHandlerClass(Map<String, Object> metadata) {
        Object api = metadata.get("api");
        if (!(api instanceof Map<?, ?> apiMap)) {
            return Optional.empty();
        }
        Object handler = apiMap.get("handler");
        if (handler == null || handler.toString().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(handler.toString());
    }

}
