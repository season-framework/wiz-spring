package com.wiz.runtime;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.wiz.core.ProjectJavaNaming;
import com.wiz.session.AuthService;
import com.wiz.session.SessionService;

import jakarta.servlet.http.HttpSession;

final class ProjectExtensionLoader {

    private ProjectExtensionLoader() {
    }

    static SessionService session(WizContext context, HttpSession httpSession) {
        for (String className : sessionCandidates(context)) {
            Optional<SessionService> service = instantiate(context, className, SessionService.class, httpSession, HttpSession.class);
            if (service.isPresent()) {
                return service.get();
            }
        }
        return new SessionService(httpSession);
    }

    static AuthService auth(WizContext context) {
        for (String className : authCandidates(context)) {
            Optional<AuthService> service = instantiate(context, className, AuthService.class, context, WizContext.class);
            if (service.isPresent()) {
                return service.get();
            }
        }
        return new AuthService();
    }

    private static List<String> sessionCandidates(WizContext context) {
        String root = ProjectJavaNaming.packageRoot(context.project().name());
        return configured(context, List.of(
                "wiz.session.service-class",
                "wiz.session.handler",
                "wiz.session.class-name"))
                .map(List::of)
                .orElseGet(() -> List.of(
                        root + ".model.SessionService",
                        root + ".model.session.SessionService",
                        root + ".model.session.ProjectSessionService",
                        root + ".session.SessionService",
                        root + ".session.ProjectSessionService"));
    }

    private static List<String> authCandidates(WizContext context) {
        String root = ProjectJavaNaming.packageRoot(context.project().name());
        return configured(context, List.of(
                "wiz.auth.service-class",
                "wiz.auth.handler",
                "wiz.auth.class-name"))
                .map(List::of)
                .orElseGet(() -> List.of(
                        root + ".model.AuthService",
                        root + ".model.auth.AuthService",
                        root + ".model.auth.ProjectAuthService",
                        root + ".auth.AuthService",
                        root + ".auth.ProjectAuthService"));
    }

    private static Optional<String> configured(WizContext context, List<String> keys) {
        Map<String, Object> values;
        try {
            values = context.config().namespace("application").values();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        return keys.stream()
                .map(values::get)
                .filter(value -> value != null && !value.toString().isBlank())
                .map(Object::toString)
                .findFirst();
    }

    private static <T> Optional<T> instantiate(WizContext context, String className, Class<T> type, Object preferredArgument, Class<?> preferredArgumentType) {
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        URLClassLoader loader;
        try {
            loader = new URLClassLoader(ProjectClassPath.apiUrls(context.project()), previousLoader);
        } catch (IOException exception) {
            return Optional.empty();
        }
        try {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> candidate = Class.forName(className, true, loader);
            if (!type.isAssignableFrom(candidate)) {
                close(loader);
                return Optional.empty();
            }
            Object instance = construct(candidate, preferredArgument, preferredArgumentType);
            context.onCleanup(() -> close(loader));
            return Optional.of(type.cast(instance));
        } catch (ClassNotFoundException exception) {
            close(loader);
            return Optional.empty();
        } catch (ReflectiveOperationException exception) {
            close(loader);
            throw new IllegalArgumentException("Failed to create project extension: " + className, exception);
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }

    private static Object construct(Class<?> type, Object preferredArgument, Class<?> preferredArgumentType) throws ReflectiveOperationException {
        Constructor<?> constructor = findConstructor(type, preferredArgument == null ? preferredArgumentType : preferredArgument.getClass());
        if (constructor != null) {
            constructor.setAccessible(true);
            return constructor.newInstance(preferredArgument);
        }
        Constructor<?> defaultConstructor = type.getDeclaredConstructor();
        defaultConstructor.setAccessible(true);
        return defaultConstructor.newInstance();
    }

    private static Constructor<?> findConstructor(Class<?> type, Class<?> argumentType) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 1 && constructor.getParameterTypes()[0].isAssignableFrom(argumentType)) {
                return constructor;
            }
        }
        return null;
    }

    private static void close(URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to close project extension classloader", exception);
        }
    }
}
