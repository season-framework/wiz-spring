package com.wiz.runtime;

import java.beans.Introspector;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.ResourceBundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Clears parent-loaded Spring reflection caches that can otherwise retain
 * methods and classes from a retired project classloader through soft entries.
 */
final class ProjectSpringCacheCleanup {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectSpringCacheCleanup.class);

    private ProjectSpringCacheCleanup() {
    }

    static void clearFor(ClassLoader projectClassLoader) {
        if (projectClassLoader == null) {
            return;
        }
        run("Spring bean introspection", () -> CachedIntrospectionResults.clearClassLoader(projectClassLoader));
        run("Spring reflection", ReflectionUtils::clearCache);
        run("Spring resolvable types", ResolvableType::clearCache);
        run("Spring annotations", AnnotationUtils::clearCache);
        run("Spring bridge methods", () -> clearMap(
                "org.springframework.core.BridgeMethodResolver", "cache"));
        run("Spring @Bean annotations", () -> invokeStatic(
                "org.springframework.context.annotation.BeanAnnotationHelper", "clearCaches"));
        run("JDK bean introspection", Introspector::flushCaches);
        run("JDK resource bundles", () -> ResourceBundle.clearCache(projectClassLoader));
    }

    private static void clearMap(String className, String fieldName) throws ReflectiveOperationException {
        Class<?> owner = parentClass(className);
        Field field = owner.getDeclaredField(fieldName);
        if (!field.trySetAccessible()) {
            throw new IllegalAccessException("Cannot access " + className + "." + fieldName);
        }
        Object value = field.get(null);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException(className + "." + fieldName + " is not a Map");
        }
        map.clear();
    }

    private static void invokeStatic(String className, String methodName) throws ReflectiveOperationException {
        Class<?> owner = parentClass(className);
        Method method = owner.getDeclaredMethod(methodName);
        if (!method.trySetAccessible()) {
            throw new IllegalAccessException("Cannot access " + className + "." + methodName + "()");
        }
        try {
            method.invoke(null);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw exception;
        }
    }

    private static Class<?> parentClass(String className) throws ClassNotFoundException {
        return Class.forName(className, false, ProjectSpringCacheCleanup.class.getClassLoader());
    }

    private static void run(String cache, CacheCleanup cleanup) {
        try {
            cleanup.clear();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            LOGGER.warn("Failed to clear {} cache while retiring a project runtime", cache, exception);
        }
    }

    @FunctionalInterface
    private interface CacheCleanup {
        void clear() throws ReflectiveOperationException;
    }
}
