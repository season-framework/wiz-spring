package com.wiz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Validated
@ConfigurationProperties(prefix = "wiz.runtime")
public class WizRuntimeProperties {

    private static final String SAFE_NAME = "^[A-Za-z0-9][A-Za-z0-9._-]*$";

    @NotBlank
    @Pattern(regexp = SAFE_NAME, message = "must be a safe cookie name")
    private String devmodeCookieName = "season-wiz-devmode";

    private boolean warmupEnabled = true;

    public String getDevmodeCookieName() {
        return devmodeCookieName;
    }

    public void setDevmodeCookieName(String devmodeCookieName) {
        this.devmodeCookieName = trim(devmodeCookieName);
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
