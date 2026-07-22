package com.wiz.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.wiz.runtime.PathService;
import com.wiz.runtime.WorkspaceMetadata;

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
        Path current = Files.isRegularFile(normalized) ? normalized.getParent() : normalized;
        while (current != null) {
            Optional<Path> marker = workspaceMarker(current);
            if (marker.isPresent()) {
                Optional<WorkspaceMetadata> parsed;
                try {
                    parsed = new PathService(current).workspaceMetadata(current);
                } catch (RuntimeException exception) {
                    throw invalidMarker(marker.get(), "invalid YAML: " + exception.getMessage());
                }
                WorkspaceMetadata metadata = parsed
                        .orElseThrow(() -> invalidMarker(marker.get(), "the 'workspace' field is missing"));
                if (!metadata.isJava()) {
                    throw invalidMarker(marker.get(), "workspace must be 'java', but was '" + metadata.workspace() + "'");
                }
                validateStructure(current);
                return current;
            }
            current = current.getParent();
        }
        throw notFound(normalized, envVar, commandName);
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
                .append(". Expected config/wiz.yml with 'workspace: java'. Run ");
        if (commandName == null || commandName.isBlank()) {
            message.append("this command");
        } else {
            message.append("wiz-spring ").append(commandName);
        }
        message.append(" from a WIZ Spring workspace root/subdirectory or pass --root <path>");
        if (envVar != null && !envVar.isBlank()) {
            message.append(" or set ").append(envVar);
        }
        message.append(". Create or migrate the workspace with wiz-spring create before retrying.");
        return new IllegalArgumentException(message.toString());
    }

    private static Optional<Path> workspaceMarker(Path root) {
        return List.of(root.resolve("config/wiz.yml"), root.resolve("config/wiz.yaml")).stream()
                .filter(Files::isRegularFile)
                .findFirst();
    }

    private static void validateStructure(Path root) {
        boolean applicationConfig = Files.isRegularFile(root.resolve("config/application.yml"))
                || Files.isRegularFile(root.resolve("config/application.yaml"));
        if (!applicationConfig) {
            throw new IllegalArgumentException("Invalid WIZ Spring workspace at " + root
                    + ": config/application.yml (or application.yaml) is required. "
                    + "Restore the workspace configuration or recreate it with wiz-spring create.");
        }
        boolean sourceLayout = Files.isDirectory(root.resolve("src/app"));
        boolean bundleLayout = Files.isDirectory(root.resolve("bundle/src/app"));
        if (!sourceLayout && !bundleLayout) {
            throw new IllegalArgumentException("Invalid WIZ Spring workspace at " + root
                    + ": expected src/app or bundle/src/app. "
                    + "Run wiz-spring create for a new workspace or restore the missing WIZ sources.");
        }
    }

    private static IllegalArgumentException invalidMarker(Path marker, String reason) {
        return new IllegalArgumentException("Invalid WIZ Spring workspace marker " + marker + ": " + reason
                + ". Expected config/wiz.yml with 'workspace: java'; use wiz-spring create or migrate the workspace metadata.");
    }
}
