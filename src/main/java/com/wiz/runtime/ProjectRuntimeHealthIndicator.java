package com.wiz.runtime;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("projectRuntimeHealth")
public class ProjectRuntimeHealthIndicator implements HealthIndicator {

    private final ProjectObservabilityRegistry observability;

    public ProjectRuntimeHealthIndicator(ProjectObservabilityRegistry observability) {
        this.observability = observability == null ? new ProjectObservabilityRegistry() : observability;
    }

    @Override
    public Health health() {
        Health.Builder builder = observability.hasDownResource() ? Health.down() : Health.up();
        return builder.withDetail("resources", observability.healthSnapshot()).build();
    }
}
