package com.wiz.runtime.internal;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Runs inside a project classloader so DriverManager's caller visibility check
 * exposes JDBC drivers registered by that classloader.
 */
public final class ProjectJdbcDriverCleanup {

    private ProjectJdbcDriverCleanup() {
    }

    public static int deregisterJdbcDrivers() throws SQLException {
        ClassLoader projectClassLoader = ProjectJdbcDriverCleanup.class.getClassLoader();
        Enumeration<Driver> registered = DriverManager.getDrivers();
        List<Driver> projectDrivers = new ArrayList<>();
        while (registered.hasMoreElements()) {
            Driver driver = registered.nextElement();
            if (driver.getClass().getClassLoader() == projectClassLoader) {
                projectDrivers.add(driver);
            }
        }
        SQLException failure = null;
        for (Driver driver : projectDrivers) {
            try {
                DriverManager.deregisterDriver(driver);
            } catch (SQLException | RuntimeException exception) {
                SQLException cleanupFailure = exception instanceof SQLException sqlException
                        ? sqlException
                        : new SQLException("JDBC driver cleanup callback failed: "
                                + driver.getClass().getName(), exception);
                if (failure == null) {
                    failure = cleanupFailure;
                } else {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return projectDrivers.size();
    }
}
