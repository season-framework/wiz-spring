package com.wiz.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.BridgeMethodResolver;

class ProjectRuntimeClassLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void closeDeregistersJdbcDriversOwnedByProjectClassLoader() throws Exception {
        String property = "wiz.test.jdbc-driver-cleaned." + System.nanoTime();
        Driver parentDriver = new ParentDriver();
        Path source = tempDir.resolve("src/example/ReviewDriver.java");
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, driverSource(property));

        int compileExit = ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                "-d",
                classes.toString(),
                source.toString());
        assertEquals(0, compileExit);
        assertNull(System.getProperty(property));

        ProjectRuntimeClassLoader loader = new ProjectRuntimeClassLoader(
                new URL[] {classes.toUri().toURL()},
                ProjectRuntimeClassLoader.class.getClassLoader());
        DriverManager.registerDriver(parentDriver);
        try {
            Class.forName("example.ReviewDriver", true, loader);
            assertNull(System.getProperty(property));
            loader.close();
            assertEquals("true", System.getProperty(property));
            assertTrue(DriverManager.drivers().anyMatch(driver -> driver == parentDriver));
        } finally {
            loader.close();
            DriverManager.deregisterDriver(parentDriver);
            System.clearProperty(property);
        }
    }

    @Test
    void closeContinuesDeregisteringDriversAfterCleanupCallbackFailure() throws Exception {
        String firstProperty = "wiz.test.jdbc-driver-first." + System.nanoTime();
        String secondProperty = "wiz.test.jdbc-driver-second." + System.nanoTime();
        Path source = tempDir.resolve("failure-src/example/ReviewFailingDrivers.java");
        Path classes = tempDir.resolve("failure-classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, failingDriversSource(firstProperty, secondProperty));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-d", classes.toString(), source.toString()));

        ProjectRuntimeClassLoader loader = new ProjectRuntimeClassLoader(
                new URL[] {classes.toUri().toURL()},
                ProjectRuntimeClassLoader.class.getClassLoader());
        try {
            Class.forName("example.ReviewFailingDrivers", true, loader);

            IOException failure = assertThrows(IOException.class, loader::close);

            assertTrue(failure.getMessage().contains("Failed to deregister"));
            assertEquals("attempted", System.getProperty(firstProperty));
            assertEquals("cleaned", System.getProperty(secondProperty));
        } finally {
            loader.close();
            System.clearProperty(firstProperty);
            System.clearProperty(secondProperty);
        }
    }

    @Test
    void closeClearsParentSpringCachesThatReferenceProjectMethods() throws Exception {
        Path source = tempDir.resolve("spring-src/example/ReviewBean.java");
        Path classes = tempDir.resolve("spring-classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, """
                package example;

                interface ReviewContract<T> {
                    T value();
                }

                public final class ReviewBean implements ReviewContract<String> {
                    @Override
                    public String value() {
                        return "review";
                    }

                    public String bean() {
                        return "bean";
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-d", classes.toString(), source.toString()));

        ProjectRuntimeClassLoader loader = new ProjectRuntimeClassLoader(
                new URL[] {classes.toUri().toURL()},
                ProjectRuntimeClassLoader.class.getClassLoader());
        try {
            Class<?> bean = Class.forName("example.ReviewBean", true, loader);
            Method bridge = java.util.Arrays.stream(bean.getDeclaredMethods())
                    .filter(Method::isBridge)
                    .findFirst()
                    .orElseThrow();
            BridgeMethodResolver.findBridgedMethod(bridge);

            Class<?> beanAnnotationHelper = Class.forName(
                    "org.springframework.context.annotation.BeanAnnotationHelper");
            Method determineBeanName = beanAnnotationHelper.getDeclaredMethod(
                    "determineBeanNameFor", Method.class);
            assertTrue(determineBeanName.trySetAccessible());
            determineBeanName.invoke(null, bean.getDeclaredMethod("bean"));

            Map<?, ?> bridgeCache = staticMap(
                    BridgeMethodResolver.class, "cache");
            Map<?, ?> beanNameCache = staticMap(
                    beanAnnotationHelper, "beanNameCache");
            assertTrue(referencesLoader(bridgeCache, loader));
            assertTrue(referencesLoader(beanNameCache, loader));

            loader.close();

            assertFalse(referencesLoader(bridgeCache, loader));
            assertFalse(referencesLoader(beanNameCache, loader));
        } finally {
            loader.close();
        }
    }

    private Map<?, ?> staticMap(Class<?> owner, String fieldName) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        assertTrue(field.trySetAccessible());
        return (Map<?, ?>) field.get(null);
    }

    private boolean referencesLoader(Map<?, ?> map, ClassLoader loader) {
        return map.entrySet().stream()
                .anyMatch(entry -> ownedBy(entry.getKey(), loader) || ownedBy(entry.getValue(), loader));
    }

    private boolean ownedBy(Object value, ClassLoader loader) {
        if (value instanceof Class<?> type) {
            return type.getClassLoader() == loader;
        }
        if (value instanceof java.lang.reflect.Member member) {
            return member.getDeclaringClass().getClassLoader() == loader;
        }
        return false;
    }

    private static final class ParentDriver implements Driver {

        @Override
        public Connection connect(String url, Properties info) {
            return null;
        }

        @Override
        public boolean acceptsURL(String url) {
            return false;
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }

    private String driverSource(String property) {
        return """
                package example;

                import java.sql.Connection;
                import java.sql.Driver;
                import java.sql.DriverManager;
                import java.sql.DriverPropertyInfo;
                import java.sql.SQLException;
                import java.sql.SQLFeatureNotSupportedException;
                import java.util.Properties;
                import java.util.logging.Logger;

                public final class ReviewDriver implements Driver {
                    static {
                        try {
                            DriverManager.registerDriver(
                                    new ReviewDriver(),
                                    () -> System.setProperty("%s", "true"));
                        } catch (SQLException exception) {
                            throw new ExceptionInInitializerError(exception);
                        }
                    }

                    public Connection connect(String url, Properties info) { return null; }
                    public boolean acceptsURL(String url) { return false; }
                    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
                        return new DriverPropertyInfo[0];
                    }
                    public int getMajorVersion() { return 1; }
                    public int getMinorVersion() { return 0; }
                    public boolean jdbcCompliant() { return false; }
                    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                        throw new SQLFeatureNotSupportedException();
                    }
                }
                """.formatted(property);
    }

    private String failingDriversSource(String firstProperty, String secondProperty) {
        return """
                package example;

                import java.sql.Connection;
                import java.sql.Driver;
                import java.sql.DriverManager;
                import java.sql.DriverPropertyInfo;
                import java.sql.SQLException;
                import java.sql.SQLFeatureNotSupportedException;
                import java.util.Properties;
                import java.util.logging.Logger;

                public final class ReviewFailingDrivers {
                    static {
                        try {
                            DriverManager.registerDriver(new ReviewDriver(), () -> {
                                if (System.getProperty("%s") == null) {
                                    System.setProperty("%s", "attempted");
                                    throw new IllegalStateException("simulated cleanup callback failure");
                                }
                            });
                            DriverManager.registerDriver(new ReviewDriver(),
                                    () -> System.setProperty("%s", "cleaned"));
                        } catch (SQLException exception) {
                            throw new ExceptionInInitializerError(exception);
                        }
                    }

                    private static final class ReviewDriver implements Driver {
                        public Connection connect(String url, Properties info) { return null; }
                        public boolean acceptsURL(String url) { return false; }
                        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
                            return new DriverPropertyInfo[0];
                        }
                        public int getMajorVersion() { return 1; }
                        public int getMinorVersion() { return 0; }
                        public boolean jdbcCompliant() { return false; }
                        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                            throw new SQLFeatureNotSupportedException();
                        }
                    }
                }
                """.formatted(firstProperty, firstProperty, secondProperty);
    }
}
