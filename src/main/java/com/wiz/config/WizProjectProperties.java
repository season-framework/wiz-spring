package com.wiz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Validated
@ConfigurationProperties(prefix = "wiz.project")
public class WizProjectProperties {

    private static final String SAFE_NAME = "^[A-Za-z0-9][A-Za-z0-9._-]*$";

    @NotBlank
    @Pattern(regexp = SAFE_NAME, message = "must be a safe project name")
    private String defaultName = "main";

    @NotBlank
    @Pattern(regexp = SAFE_NAME, message = "must be a safe cookie name")
    private String cookieName = "season-wiz-project";

    @NotBlank
    @Pattern(regexp = SAFE_NAME, message = "must be a safe cookie name")
    private String devmodeCookieName = "season-wiz-devmode";

    private boolean cookieSelectionEnabled = true;

    private boolean warmupEnabled = true;

    public String getDefaultName() {
        return defaultName;
    }

    public void setDefaultName(String defaultName) {
        this.defaultName = trim(defaultName);
    }

    public String getCookieName() {
        return cookieName;
    }

    public void setCookieName(String cookieName) {
        this.cookieName = trim(cookieName);
    }

    public String getDevmodeCookieName() {
        return devmodeCookieName;
    }

    public void setDevmodeCookieName(String devmodeCookieName) {
        this.devmodeCookieName = trim(devmodeCookieName);
    }

    public boolean isCookieSelectionEnabled() {
        return cookieSelectionEnabled;
    }

    public void setCookieSelectionEnabled(boolean cookieSelectionEnabled) {
        this.cookieSelectionEnabled = cookieSelectionEnabled;
    }

    public boolean isWarmupEnabled() {
        return warmupEnabled;
    }

    public void setWarmupEnabled(boolean warmupEnabled) {
        this.warmupEnabled = warmupEnabled;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
