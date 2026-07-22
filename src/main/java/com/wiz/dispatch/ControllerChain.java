package com.wiz.dispatch;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.wiz.core.ProjectJavaNaming;
import com.wiz.runtime.ProjectRuntimeCache;
import com.wiz.runtime.ProjectRuntimeCache.ProjectControllerHook;
import com.wiz.runtime.ProjectRuntimeCache.ProjectReflectionException;
import com.wiz.runtime.WizBadRequestException;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ControllerChain {

    public static final String DEFAULT_CONTROLLER_NAME = "base";

    private static final Logger LOGGER = LoggerFactory.getLogger(ControllerChain.class);

    private final ProjectRuntimeCache runtimeCache;

    public ControllerChain() {
        this(new ProjectRuntimeCache());
    }

    @Autowired
    public ControllerChain(ProjectRuntimeCache runtimeCache) {
        this.runtimeCache = runtimeCache == null ? new ProjectRuntimeCache() : runtimeCache;
    }

    public Optional<WizResult> before(WizContext context, Map<String, Object> appMetadata) {
        List<String> controllerNames = controllerNames(appMetadata);
        if (controllerNames.isEmpty()) {
            return Optional.empty();
        }

        ProjectRuntimeCache.CachedProjectRuntime runtime = context.projectRuntime();
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(runtime.classLoader());
        try {
            for (String controllerName : controllerNames) {
                Optional<WizResult> builtInResult = builtInBefore(context, controllerName);
                if (builtInResult.isPresent()) {
                    return builtInResult;
                }
                Optional<WizResult> result = invokeBefore(context, runtime, controllerClass(context, controllerName));
                if (result.isPresent()) {
                    return result;
                }
            }
            return Optional.empty();
        } catch (ProjectReflectionException exception) {
            LOGGER.error("WIZ controller chain metadata lookup failed: controllers={}", controllerNames, exception);
            return Optional.of(context.response().status(500, Map.of("error", "controller chain failed")));
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }

    private Optional<WizResult> builtInBefore(WizContext context, String controllerName) {
        String normalized = controllerName == null ? "" : controllerName.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals(DEFAULT_CONTROLLER_NAME)) {
            context.response().data("session", context.session().toMap());
            return Optional.empty();
        }
        if (normalized.equals("user")) {
            context.response().data("session", context.session().toMap());
            return Optional.ofNullable(context.auth().requireUser(context));
        }
        if (normalized.equals("admin")) {
            context.response().data("session", context.session().toMap());
            return Optional.ofNullable(context.auth().requireAdmin(context));
        }
        return Optional.empty();
    }

    private Optional<WizResult> invokeBefore(WizContext context, ProjectRuntimeCache.CachedProjectRuntime runtime, String controllerClass) {
        try {
            ProjectControllerHook hookMetadata = runtime.controllerHook(controllerClass).orElse(null);
            if (hookMetadata == null) {
                return Optional.empty();
            }
            Object controller = hookMetadata.constructor().newInstance();
            if (hookMetadata.implementsControllerHook()) {
                ControllerHook hook = (ControllerHook) controller;
                return Optional.ofNullable(hook.before(context));
            }

            var before = hookMetadata.beforeMethod();
            Object value = before.getParameterCount() == 1 ? before.invoke(controller, context) : before.invoke(controller);
            if (value instanceof WizResult result) {
                return Optional.of(result);
            }
            return Optional.empty();
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof WizBadRequestException badRequest) {
                return Optional.of(context.response().status(400, badRequest.data()));
            }
            LOGGER.error("WIZ controller hook invocation failed: controller={}", controllerClass,
                    exception.getCause() == null ? exception : exception.getCause());
            return Optional.of(context.response().status(500, Map.of("error", "controller chain failed")));
        } catch (ReflectiveOperationException exception) {
            LOGGER.error("WIZ controller hook reflection failed: controller={}", controllerClass, exception);
            return Optional.of(context.response().status(500, Map.of("error", "controller chain failed")));
        }
    }

    private List<String> controllerNames(Map<String, Object> appMetadata) {
        Object controller = appMetadata.get("controller");
        if (controller == null || controller.toString().isBlank()) {
            return List.of(DEFAULT_CONTROLLER_NAME);
        }
        Set<String> names = new LinkedHashSet<>();
        java.util.Arrays.stream(controller.toString().split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .flatMap(value -> controllerChain(value).stream())
                .forEach(names::add);
        return List.copyOf(names);
    }

    private List<String> controllerChain(String controllerName) {
        String builtIn = builtInName(controllerName);
        return switch (builtIn) {
            case "admin" -> List.of(DEFAULT_CONTROLLER_NAME, "user", "admin");
            case "user" -> List.of(DEFAULT_CONTROLLER_NAME, "user");
            case DEFAULT_CONTROLLER_NAME -> List.of(DEFAULT_CONTROLLER_NAME);
            default -> List.of(controllerName);
        };
    }

    private String builtInName(String controllerName) {
        String normalized = controllerName == null ? "" : controllerName.trim().toLowerCase(java.util.Locale.ROOT).replace('\\', '/');
        if (normalized.startsWith("portal/season/")) {
            normalized = normalized.substring("portal/season/".length());
        }
        return normalized;
    }

    private String controllerClass(WizContext context, String controllerName) {
        return ProjectJavaNaming.controllerHookClass(context.project(), controllerName);
    }

}
