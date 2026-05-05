package com.wiz.dispatch;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.wiz.core.ProjectJavaNaming;
import com.wiz.runtime.ProjectClassPath;
import com.wiz.runtime.WizBadRequestException;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;
import org.springframework.stereotype.Service;

@Service
public class ControllerChain {

    public static final String DEFAULT_CONTROLLER_NAME = "base";

    public Optional<WizResult> before(WizContext context, Map<String, Object> appMetadata) {
        List<String> controllerNames = controllerNames(appMetadata);
        if (controllerNames.isEmpty()) {
            return Optional.empty();
        }

        try (URLClassLoader loader = new URLClassLoader(ProjectClassPath.apiUrls(context.project()), Thread.currentThread().getContextClassLoader())) {
            ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                for (String controllerName : controllerNames) {
                    Optional<WizResult> builtInResult = builtInBefore(context, controllerName);
                    if (builtInResult.isPresent()) {
                        return builtInResult;
                    }
                    Optional<WizResult> result = invokeBefore(context, loader, controllerClass(context, controllerName));
                    if (result.isPresent()) {
                        return result;
                    }
                }
                return Optional.empty();
            } finally {
                Thread.currentThread().setContextClassLoader(previousLoader);
            }
        } catch (IOException exception) {
            return Optional.of(context.response().status(500, Map.of("error", "controller chain failed")));
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

    private Optional<WizResult> invokeBefore(WizContext context, ClassLoader loader, String controllerClass) {
        try {
            Class<?> controllerType = Class.forName(controllerClass, true, loader);
            Object controller = controllerType.getDeclaredConstructor().newInstance();
            if (controller instanceof ControllerHook hook) {
                return Optional.ofNullable(hook.before(context));
            }

            Method before = findBeforeMethod(controllerType);
            if (before == null) {
                return Optional.empty();
            }
            before.setAccessible(true);
            Object value = before.getParameterCount() == 1 ? before.invoke(controller, context) : before.invoke(controller);
            if (value instanceof WizResult result) {
                return Optional.of(result);
            }
            return Optional.empty();
        } catch (ClassNotFoundException exception) {
            return Optional.empty();
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof WizBadRequestException badRequest) {
                return Optional.of(context.response().status(400, badRequest.data()));
            }
            return Optional.of(context.response().status(500, Map.of("error", "controller chain failed")));
        } catch (ReflectiveOperationException exception) {
            return Optional.of(context.response().status(500, Map.of("error", "controller chain failed")));
        }
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
        return ProjectJavaNaming.controllerHookClass(context.project().name(), controllerName);
    }

}
