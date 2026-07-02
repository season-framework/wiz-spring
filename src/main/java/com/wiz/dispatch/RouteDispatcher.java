package com.wiz.dispatch;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.wiz.runtime.ProjectRuntimeCache;
import com.wiz.runtime.ProjectRuntimeCache.ProjectReflectionException;
import com.wiz.runtime.ProjectRuntimeCache.ProjectRouteHandler;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizRequest;
import com.wiz.runtime.WizResult;
import com.wiz.runtime.WizRouteMatcher;
import com.wiz.runtime.WizRuntime;
import com.wiz.runtime.WizSegment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RouteDispatcher {

    private final WizRuntime runtime;
    private final RouteRegistry routeRegistry;
    private final ControllerChain controllerChain;
    private final Map<String, RouteHandler> handlers;
    private final ProjectRuntimeCache runtimeCache;
    private final WizRouteMatcher routeMatcher = new WizRouteMatcher();

    @Autowired
    public RouteDispatcher(WizRuntime runtime, RouteRegistry routeRegistry, ControllerChain controllerChain, List<RouteHandler> handlers, ProjectRuntimeCache runtimeCache) {
        this.runtime = runtime;
        this.routeRegistry = routeRegistry;
        this.controllerChain = controllerChain;
        this.runtimeCache = runtimeCache == null ? runtime.runtimeCache() : runtimeCache;
        this.handlers = new LinkedHashMap<>();
        handlers.forEach(handler -> this.handlers.put(handler.routeId(), handler));
    }

    public RouteDispatcher(WizRuntime runtime, RouteRegistry routeRegistry, ControllerChain controllerChain, List<RouteHandler> handlers) {
        this.runtime = runtime;
        this.routeRegistry = routeRegistry;
        this.controllerChain = controllerChain;
        this.runtimeCache = runtime.runtimeCache();
        this.handlers = new LinkedHashMap<>();
        handlers.forEach(handler -> this.handlers.put(handler.routeId(), handler));
    }

    public Optional<WizResult> dispatch(WizRequest request) {
        try (WizContext context = runtime.createContext(request)) {
            for (RouteDefinition definition : routeRegistry.definitions(context.project())) {
                if (!definition.acceptsMethod(request.method())) {
                    continue;
                }
                Optional<WizSegment> segment = routeMatcher.match(definition.route(), request.path());
                if (segment.isEmpty()) {
                    continue;
                }

                Optional<WizResult> controllerResult = controllerChain.before(context, Map.of("controller", definition.controllerName()));
                if (controllerResult.isPresent()) {
                    return controllerResult;
                }

                RouteHandler handler = handlers.get(definition.id());
                if (handler == null) {
                    return Optional.of(invokeProjectHandler(context, definition, segment.get()));
                }
                return Optional.of(handler.handle(context, segment.get()));
            }
            return Optional.empty();
        }
    }

    private WizResult invokeProjectHandler(WizContext context, RouteDefinition definition, WizSegment segment) {
        if (definition.handlerClass() == null || definition.handlerClass().isBlank()) {
            return context.response().status(404, Map.of("error", "route handler not found"));
        }
        ProjectRuntimeCache.CachedProjectRuntime projectRuntime = runtimeCache.get(context.project());
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(projectRuntime.classLoader());
        try {
            ProjectRouteHandler routeHandlerMetadata = projectRuntime.routeHandler(definition.handlerClass()).orElse(null);
            if (routeHandlerMetadata == null) {
                return context.response().status(404, Map.of("error", "route handler not found"));
            }
            Object handler = routeHandlerMetadata.constructor().newInstance();
            if (routeHandlerMetadata.implementsRouteHandler()) {
                RouteHandler routeHandler = (RouteHandler) handler;
                return routeHandler.handle(context, segment);
            }
            var handle = routeHandlerMetadata.handleMethod();
            Object value = handle.invoke(handler, context, segment);
            return value instanceof WizResult result ? result : context.response().status(204);
        } catch (InvocationTargetException exception) {
            return context.response().status(500, Map.of("error", "route handler failed"));
        } catch (ReflectiveOperationException | ProjectReflectionException exception) {
            return context.response().status(500, Map.of("error", "route handler failed"));
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }

}
