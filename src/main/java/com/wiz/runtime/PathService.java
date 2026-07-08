package com.wiz.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

@Service
public class PathService {

    public static final String APP_NAME = "main";
    public static final String DEFAULT_PACKAGE_ROOT = "com.wiz.app";

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

    public ProjectContext workspaceContext() {
        return workspaceContext(packageRoot());
    }

    public ProjectContext workspaceContext(String packageRoot) {
        Path workspaceRoot = root;
        return new ProjectContext(
                APP_NAME,
                validatePackageRoot(packageRoot),
                workspaceRoot,
                workspaceRoot.resolve("src"),
                workspaceRoot.resolve("src/app"),
                workspaceRoot.resolve("src/model"),
                workspaceRoot.resolve("src/route"),
                workspaceRoot.resolve("src/assets"),
                workspaceRoot.resolve("config"),
                workspaceRoot.resolve("build"),
                workspaceRoot.resolve("bundle"));
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
        return isJavaWorkspace(candidate);
    }

    public boolean isJavaWorkspace(Path candidate) {
        return (Files.isRegularFile(candidate.resolve("config/application.yml"))
                || Files.isRegularFile(candidate.resolve("config/application.yaml"))
                || Files.isRegularFile(candidate.resolve("config/wiz.yml"))
                || Files.isRegularFile(candidate.resolve("config/wiz.yaml")))
                && (Files.isDirectory(candidate.resolve("src"))
                        || Files.isDirectory(candidate.resolve("bundle"))
                        || Files.isRegularFile(candidate.resolve("pom.xml"))
                        || Files.isRegularFile(candidate.resolve("config/wiz.yml"))
                        || Files.isRegularFile(candidate.resolve("config/wiz.yaml")));
    }

    public String packageRoot() {
        for (Path config : List.of(
                root.resolve("config/application.yml"),
                root.resolve("config/application.yaml"),
                root.resolve("config/wiz.yml"),
                root.resolve("config/wiz.yaml"))) {
            String value = yaml(config).getProperty("wiz.java.package-root");
            if (value == null || value.isBlank()) {
                value = yaml(config).getProperty("wiz.java.packageRoot");
            }
            if (value != null && !value.isBlank()) {
                return validatePackageRoot(value.trim());
            }
        }
        return DEFAULT_PACKAGE_ROOT;
    }

    public String validatePackageRoot(String packageRoot) {
        if (packageRoot == null || packageRoot.isBlank()) {
            throw new IllegalArgumentException("Java package root is required");
        }
        String value = packageRoot.trim();
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) {
            throw new IllegalArgumentException("Java package root must be a valid Java package name");
        }
        if (value.startsWith("java.") || value.equals("java")) {
            throw new IllegalArgumentException("Java package root must not use the java namespace");
        }
        return value;
    }

    private Properties yaml(Path path) {
        if (!Files.isRegularFile(path)) {
            return new Properties();
        }
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new FileSystemResource(path));
        Properties properties = factory.getObject();
        return properties == null ? new Properties() : properties;
    }
}
