package com.wiz.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

import com.wiz.runtime.internal.ProjectJdbcDriverCleanup;

final class ProjectRuntimeClassLoader extends URLClassLoader {

    private static final String CLEANUP_CLASS_NAME = ProjectJdbcDriverCleanup.class.getName();
    private static final String CLEANUP_CLASS_RESOURCE = "/"
            + CLEANUP_CLASS_NAME.replace('.', '/') + ".class";

    ProjectRuntimeClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    int deregisterJdbcDrivers() throws IOException {
        try {
            Class<?> cleanupClass = childCleanupClass();
            Method cleanup = cleanupClass.getMethod("deregisterJdbcDrivers");
            return ((Number) cleanup.invoke(null)).intValue();
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IOException("Failed to deregister project JDBC drivers", cause);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            throw new IOException("Failed to initialize project JDBC driver cleanup", exception);
        }
    }

    private Class<?> childCleanupClass() throws IOException {
        Class<?> loaded = findLoadedClass(CLEANUP_CLASS_NAME);
        if (loaded != null) {
            return loaded;
        }
        byte[] bytes;
        try (InputStream input = ProjectJdbcDriverCleanup.class.getResourceAsStream(CLEANUP_CLASS_RESOURCE)) {
            if (input == null) {
                throw new IOException("Project JDBC cleanup bytecode is unavailable");
            }
            bytes = input.readAllBytes();
        }
        return defineClass(CLEANUP_CLASS_NAME, bytes, 0, bytes.length);
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        ProjectSpringCacheCleanup.clearFor(this);
        try {
            deregisterJdbcDrivers();
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            super.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
