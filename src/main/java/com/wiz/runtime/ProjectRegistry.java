package com.wiz.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.wiz.config.WizProjectProperties;

import org.springframework.beans.factory.annotation.Autowired;
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
    private final boolean cookieSelectionEnabled;

    @Autowired
    public ProjectRegistry(PathService pathService, WizProjectProperties properties) {
        this(pathService,
                properties.getCookieName(),
                properties.getDevmodeCookieName(),
                properties.getDefaultName(),
                properties.isCookieSelectionEnabled());
    }

    public ProjectRegistry(PathService pathService, String projectCookieName, String devModeCookieName, String defaultProjectName) {
        this(pathService, projectCookieName, devModeCookieName, defaultProjectName, true);
    }

    public ProjectRegistry(PathService pathService, String projectCookieName, String devModeCookieName, String defaultProjectName, boolean cookieSelectionEnabled) {
        this.pathService = pathService;
        this.projectCookieName = blankDefault(projectCookieName, DEFAULT_PROJECT_COOKIE_NAME);
        this.devModeCookieName = blankDefault(devModeCookieName, DEFAULT_DEVMODE_COOKIE_NAME);
        this.defaultProjectName = blankDefault(defaultProjectName, DEFAULT_PROJECT_NAME);
        this.cookieSelectionEnabled = cookieSelectionEnabled;
    }

    public ProjectRegistry(PathService pathService) {
        this(pathService, DEFAULT_PROJECT_COOKIE_NAME, DEFAULT_DEVMODE_COOKIE_NAME, DEFAULT_PROJECT_NAME, true);
    }

    public String projectCookieName() {
        return projectCookieName;
    }

    public String devModeCookieName() {
        return devModeCookieName;
    }

    public boolean cookieSelectionEnabled() {
        return cookieSelectionEnabled;
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
        Optional<ProjectContext> cookieProject = cookieSelectionEnabled
                ? projectCookieValue
                        .filter(value -> !value.isBlank())
                        .flatMap(this::findProjectSafely)
                : Optional.empty();
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

    private String blankDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
