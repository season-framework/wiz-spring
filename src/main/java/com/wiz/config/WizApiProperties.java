package com.wiz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Pattern;

@Validated
@ConfigurationProperties(prefix = "wiz.api")
public class WizApiProperties {

    public static final String DEFAULT_PREFIX = "/wiz/api";

    @Pattern(regexp = "^/(?:[A-Za-z0-9._~-]+/)*[A-Za-z0-9._~-]+$", message = "must start with / and must not end with /")
    private String prefix = DEFAULT_PREFIX;

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        String value = prefix == null ? "" : prefix.trim();
        if (value.isBlank()) {
            this.prefix = DEFAULT_PREFIX;
            return;
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        this.prefix = value;
    }
}
