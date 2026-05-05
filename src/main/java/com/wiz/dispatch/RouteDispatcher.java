package com.wiz.dispatch;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.wiz.runtime.ProjectClassPath;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizRequest;
import com.wiz.runtime.WizResult;
import com.wiz.runtime.WizRouteMatcher;
import com.wiz.runtime.WizRuntime;
import com.wiz.runtime.WizSegment;

import org.springframework.stereotype.Service;

@Service
public class RouteDispatcher {

    private final WizRuntime runtime;
    private final RouteRegistry routeRegistry;
    private final ControllerChain controllerChain;
    private final Map<String, RouteHandler> handlers;
    private final WizRouteMatcher routeMatcher = new WizRouteMatcher();

    public RouteDispatcher(WizRuntime runtime, RouteRegistry routeRegistry, ControllerChain controllerChain, List<RouteHandler> handlers) {
        this.runtime = runtime;
        this.routeRegistry = routeRegistry;
        this.controllerChain = controllerChain;
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
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(ProjectClassPath.apiUrls(context.project()), previousLoader)) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> handlerType = Class.forName(definition.handlerClass(), true, loader);
            Object handler = handlerType.getDeclaredConstructor().newInstance();
            if (handler instanceof RouteHandler routeHandler) {
                return routeHandler.handle(context, segment);
            }
            Method handle = findHandleMethod(handlerType);
            if (handle == null) {
                return context.response().status(404, Map.of("error", "route handler not found"));
            }
            handle.setAccessible(true);
            Object value = handle.invoke(handler, context, segment);
            return value instanceof WizResult result ? result : context.response().status(204);
        } catch (ClassNotFoundException exception) {
            return context.response().status(404, Map.of("error", "route handler not found"));
        } catch (InvocationTargetException exception) {
            return context.response().status(500, Map.of("error", "route handler failed"));
        } catch (ReflectiveOperationException | IOException exception) {
            return context.response().status(500, Map.of("error", "route handler failed"));
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
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
