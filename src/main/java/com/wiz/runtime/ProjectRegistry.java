package com.wiz.runtime;

import java.util.Map;
import java.util.Optional;

import com.wiz.config.WizRuntimeProperties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProjectRegistry {

    public static final String DEFAULT_PROJECT_NAME = "main";
    public static final String DEFAULT_DEVMODE_COOKIE_NAME = "season-wiz-devmode";

    private final PathService pathService;
    private final String devModeCookieName;

    @Autowired
    public ProjectRegistry(PathService pathService, WizRuntimeProperties properties) {
        this(pathService,
                properties.getDevmodeCookieName(),
                properties.isWarmupEnabled());
    }

    public ProjectRegistry(PathService pathService, String devModeCookieName, boolean ignoredWarmupEnabled) {
        this.pathService = pathService;
        this.devModeCookieName = blankDefault(devModeCookieName, DEFAULT_DEVMODE_COOKIE_NAME);
    }

    public ProjectRegistry(PathService pathService) {
        this(pathService, DEFAULT_DEVMODE_COOKIE_NAME, true);
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

    public ProjectContext workspace() {
        return pathService.workspaceContext();
    }

    private String blankDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
