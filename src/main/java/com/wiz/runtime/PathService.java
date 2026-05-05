package com.wiz.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PathService {

    private final Path root;

    @Autowired
    public PathService(@Value("${wiz.root:.}") String root) {
        this(Path.of(root));
    }

    public PathService(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path configRoot() {
        return root.resolve("config");
    }

    public Path publicRoot() {
        return root.resolve("public");
    }

    public Path projectsRoot() {
        return root.resolve("project");
    }

    public Path projectRoot(String projectName) {
        return projectsRoot().resolve(validateProjectName(projectName));
    }

    public ProjectContext projectContext(String projectName) {
        Path projectRoot = projectRoot(projectName);
        return new ProjectContext(
                validateProjectName(projectName),
                projectRoot,
                projectRoot.resolve("src"),
                projectRoot.resolve("src/app"),
                projectRoot.resolve("src/model"),
                projectRoot.resolve("src/route"),
                projectRoot.resolve("src/assets"),
                projectRoot.resolve("config"),
                projectRoot.resolve("build"),
                projectRoot.resolve("bundle"));
    }

    public Optional<Path> findWorkspaceRoot(Path start) {
        Path current = start == null ? Path.of(".") : start;
        current = current.toAbsolutePath().normalize();
        if (Files.isRegularFile(current)) {
            current = current.getParent();
        }
        while (current != null) {
            if (isWorkspaceRoot(current)) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    public boolean isWorkspaceRoot(Path candidate) {
        return isJavaWorkspace(candidate) || isLegacyPythonWorkspace(candidate);
    }

    public boolean isJavaWorkspace(Path candidate) {
        return Files.isRegularFile(candidate.resolve("config/wiz.yml"))
                && Files.isDirectory(candidate.resolve("project"));
    }

    public boolean isLegacyPythonWorkspace(Path candidate) {
        return Files.isRegularFile(candidate.resolve("config/boot.py"))
                && Files.isRegularFile(candidate.resolve("config/ide.py"))
                && Files.isRegularFile(candidate.resolve("config/service.py"));
    }

    public String validateProjectName(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            throw new IllegalArgumentException("Project name is required");
        }
        Path candidate = Path.of(projectName);
        if (candidate.isAbsolute()
                || candidate.getNameCount() != 1
                || !candidate.normalize().equals(candidate)
                || projectName.contains("\\")) {
            throw new IllegalArgumentException("Project name must be a single safe path segment");
        }
        String value = candidate.toString();
        if (".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("Project name must be a single safe path segment");
        }
        return value;
    }
}