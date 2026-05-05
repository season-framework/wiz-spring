package com.wiz.dispatch;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

import com.wiz.core.ProjectJavaNaming;
import com.wiz.runtime.SafePath;
import com.wiz.runtime.WizBadRequestException;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizRequest;
import com.wiz.runtime.WizResult;
import com.wiz.runtime.WizRuntime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class AppApiDispatcher {

    private final WizRuntime runtime;
    private final ObjectMapper objectMapper;
    private final ControllerChain controllerChain;

    @Autowired
    public AppApiDispatcher(WizRuntime runtime, ControllerChain controllerChain) {
        this(runtime, new ObjectMapper(), controllerChain);
    }

    public AppApiDispatcher(WizRuntime runtime) {
        this(runtime, new ObjectMapper(), new ControllerChain());
    }

    public AppApiDispatcher(WizRuntime runtime, ObjectMapper objectMapper) {
        this(runtime, objectMapper, new ControllerChain());
    }

    public AppApiDispatcher(WizRuntime runtime, ObjectMapper objectMapper, ControllerChain controllerChain) {
        this.runtime = runtime;
        this.objectMapper = objectMapper;
        this.controllerChain = controllerChain;
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
                    .or(() -> Optional.of(defaultHandlerClass(context.project().name(), appId)));
            return dispatchProjectJavaApi(context, handlerClass.get(), function);
        }
    }

    private Optional<Map<String, Object>> appMetadata(WizContext context, String appId) {
        try {
            Path appRoot = context.project().bundleRoot().resolve("src/app");
            Path appJson = new SafePath(appRoot).resolveExisting(appId + "/app.json");
            Map<String, Object> metadata = objectMapper.readValue(Files.readAllBytes(appJson), new TypeReference<>() {
            });
            return Optional.of(metadata);
        } catch (IllegalArgumentException | IOException exception) {
            return Optional.empty();
        }
    }

    private WizResult dispatchProjectJavaApi(WizContext context, String handlerClass, String function) {
        try (URLClassLoader loader = new URLClassLoader(projectApiUrls(context), Thread.currentThread().getContextClassLoader())) {
            ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                Class<?> handlerType = Class.forName(handlerClass, true, loader);
                Object handler = handlerType.getDeclaredConstructor().newInstance();
                Method method = findMethod(handlerType, function);
                if (method == null) {
                    return context.response().status(404, Map.of("error", "function not found"));
                }
                method.setAccessible(true);
                Object value = method.getParameterCount() == 1 ? method.invoke(handler, context) : method.invoke(handler);
                if (value instanceof WizResult result) {
                    return result;
                }
                return context.response().ok(value);
            } finally {
                Thread.currentThread().setContextClassLoader(previousLoader);
            }
        } catch (ClassNotFoundException exception) {
            return context.response().status(500, Map.of("error", "java api handler not found"));
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof WizBadRequestException badRequest) {
                return context.response().status(400, badRequest.data());
            }
            return context.response().status(500, Map.of("error", "java api invocation failed"));
        } catch (ReflectiveOperationException | IOException exception) {
            return context.response().status(500, Map.of("error", "java api invocation failed"));
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

    private URL[] projectApiUrls(WizContext context) throws IOException {
        ArrayList<URL> urls = new ArrayList<>();
        Path classes = context.project().bundleRoot().resolve("classes");
        Path jar = context.project().bundleRoot().resolve("project-api.jar");
        if (Files.isDirectory(classes)) {
            urls.add(classes.toUri().toURL());
        }
        if (Files.isRegularFile(jar)) {
            urls.add(jar.toUri().toURL());
        }
        return urls.toArray(URL[]::new);
    }

    private Method findMethod(Class<?> handlerType, String function) {
        for (Method method : handlerType.getMethods()) {
            if (method.getName().equals(function)
                    && (method.getParameterCount() == 0
                            || (method.getParameterCount() == 1 && method.getParameterTypes()[0].isAssignableFrom(WizContext.class)))) {
                return method;
            }
        }
        return null;
    }

    private String defaultHandlerClass(String projectName, String appId) {
        return ProjectJavaNaming.appApiHandlerClass(projectName, appId);
    }
}