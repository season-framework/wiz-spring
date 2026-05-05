package com.wiz.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ProjectRegistry {

    public static final String DEFAULT_PROJECT_NAME = "main";
    public static final String DEFAULT_PROJECT_COOKIE_NAME = "season-wiz-project";
    public static final String DEFAULT_DEVMODE_COOKIE_NAME = "season-wiz-devmode";

    private final PathService pathService;
    private final String projectCookieName;
    private final String devModeCookieName;
    private final String defaultProjectName;

    @Autowired
    public ProjectRegistry(
            PathService pathService,
            @Value("${wiz.project.cookie-name:" + DEFAULT_PROJECT_COOKIE_NAME + "}") String projectCookieName,
            @Value("${wiz.project.devmode-cookie-name:" + DEFAULT_DEVMODE_COOKIE_NAME + "}") String devModeCookieName,
            @Value("${wiz.project.default-name:${wiz.default-project:" + DEFAULT_PROJECT_NAME + "}}") String defaultProjectName) {
        this.pathService = pathService;
        this.projectCookieName = projectCookieName;
        this.devModeCookieName = devModeCookieName;
        this.defaultProjectName = defaultProjectName;
    }

    public ProjectRegistry(PathService pathService) {
        this(pathService, DEFAULT_PROJECT_COOKIE_NAME, DEFAULT_DEVMODE_COOKIE_NAME, DEFAULT_PROJECT_NAME);
    }

    public String projectCookieName() {
        return projectCookieName;
    }

    public String devModeCookieName() {
        return devModeCookieName;
    }

    public boolean devMode(Map<String, String> cookies) {
        return Optional.ofNullable(cookies.get(devModeCookieName))
                .map(String::trim)
                .map(value -> value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("on"))
                .orElse(false);
    }

    public List<String> listProjects() {
        Path projectsRoot = pathService.projectsRoot();
        if (!Files.isDirectory(projectsRoot, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }

        try (Stream<Path> children = Files.list(projectsRoot)) {
            return children
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .filter(this::isSafeProjectName)
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to list WIZ projects", exception);
        }
    }

    public Optional<ProjectContext> findProject(String projectName) {
        String safeProjectName = pathService.validateProjectName(projectName);
        Path projectRoot = pathService.projectRoot(safeProjectName);
        if (!Files.isDirectory(projectRoot, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(pathService.projectContext(safeProjectName));
    }

    public ProjectContext currentProject(Map<String, String> cookies) {
        return currentProject(Optional.ofNullable(cookies.get(projectCookieName)));
    }

    public ProjectContext currentProject(Optional<String> projectCookieValue) {
        Optional<ProjectContext> cookieProject = projectCookieValue
                .filter(value -> !value.isBlank())
                .flatMap(this::findProjectSafely);
        if (cookieProject.isPresent()) {
            return cookieProject.get();
        }

        return findProjectSafely(defaultProjectName)
                .or(() -> listProjects().stream().findFirst().flatMap(this::findProjectSafely))
                .orElseThrow(() -> new IllegalStateException("No WIZ projects found under " + pathService.projectsRoot()));
    }

    private Optional<ProjectContext> findProjectSafely(String projectName) {
        try {
            return findProject(projectName);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private boolean isSafeProjectName(String projectName) {
        try {
            pathService.validateProjectName(projectName);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
