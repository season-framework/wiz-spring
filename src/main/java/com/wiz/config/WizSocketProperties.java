package com.wiz.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

@Validated
@ConfigurationProperties(prefix = "wiz.socket")
public class WizSocketProperties {

    @NotEmpty
    private List<@NotBlank String> allowedOrigins = new ArrayList<>(List.of("*"));

    @Min(0)
    private long pollingSessionTtlMillis = 120_000;

    @Min(0)
    private int maxPollingSessions = 1024;

    @Min(1)
    private int pollingQueueCapacity = 256;

    public List<String> getAllowedOrigins() {
        return List.copyOf(allowedOrigins);
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : new ArrayList<>(allowedOrigins);
    }

    public boolean isOriginAllowed(String origin) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        String candidate = origin.trim();
        return allowedOrigins.stream()
                .filter(allowedOrigin -> allowedOrigin != null)
                .map(String::trim)
                .anyMatch(allowedOrigin -> "*".equals(allowedOrigin) || allowedOrigin.equals(candidate));
    }

    public long getPollingSessionTtlMillis() {
        return pollingSessionTtlMillis;
    }

    public void setPollingSessionTtlMillis(long pollingSessionTtlMillis) {
        this.pollingSessionTtlMillis = pollingSessionTtlMillis;
    }

    public int getMaxPollingSessions() {
        return maxPollingSessions;
    }

    public void setMaxPollingSessions(int maxPollingSessions) {
        this.maxPollingSessions = maxPollingSessions;
    }

    public int getPollingQueueCapacity() {
        return pollingQueueCapacity;
    }

    public void setPollingQueueCapacity(int pollingQueueCapacity) {
        this.pollingQueueCapacity = pollingQueueCapacity;
    }
}
