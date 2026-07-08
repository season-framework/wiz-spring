package com.wiz.cli;

import java.nio.file.Path;

import com.wiz.runtime.PathService;

final class WorkspaceRootResolver {

    private WorkspaceRootResolver() {
    }

    static PathService pathService(Path start, String commandName) {
        return new PathService(resolve(start, commandName));
    }

    static Path resolve(Path start, String commandName) {
        return resolve(start, null, commandName);
    }

    static Path resolve(Path start, String envVar, String commandName) {
        Path candidate = start;
        if (candidate == null && envVar != null) {
            candidate = envPath(envVar);
        }
        Path normalized = (candidate == null ? Path.of(".") : candidate).toAbsolutePath().normalize();
        return new PathService(Path.of(".")).findWorkspaceRoot(normalized)
                .orElseThrow(() -> notFound(normalized, envVar, commandName));
    }

    private static Path envPath(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static IllegalArgumentException notFound(Path start, String envVar, String commandName) {
        StringBuilder message = new StringBuilder("WIZ Spring workspace root not found from ")
                .append(start)
                .append(". Run ");
        if (commandName == null || commandName.isBlank()) {
            message.append("this command");
        } else {
            message.append("wiz-spring ").append(commandName);
        }
        message.append(" from a WIZ Spring workspace root/subdirectory or pass --root <path>");
        if (envVar != null && !envVar.isBlank()) {
            message.append(" or set ").append(envVar);
        }
        message.append('.');
        return new IllegalArgumentException(message.toString());
    }
}
