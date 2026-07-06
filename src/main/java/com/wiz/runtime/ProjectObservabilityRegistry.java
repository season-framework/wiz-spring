package com.wiz.runtime;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class ProjectObservabilityRegistry {

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<ResourceKey, Supplier<ProjectResourceHealth>> health = new ConcurrentHashMap<>();

    public ProjectObservabilityRegistry() {
        this((MeterRegistry) null);
    }

    public ProjectObservabilityRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Autowired
    public ProjectObservabilityRegistry(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable());
    }

    public AutoCloseable registerHealth(ProjectContext project, String resource, Supplier<ProjectResourceHealth> supplier) {
        ResourceKey key = key(project, resource);
        health.put(key, supplier == null ? ProjectResourceHealth::up : supplier);
        return () -> health.remove(key);
    }

    public AutoCloseable registerGauge(ProjectContext project, String resource, String metric, Supplier<Number> supplier) {
        if (meterRegistry == null) {
            return () -> {
            };
        }
        ResourceKey key = key(project, resource);
        String meterName = "wiz.project.resource." + metricName(metric);
        Supplier<Number> valueSupplier = supplier == null ? () -> 0 : supplier;
        Meter meter = Gauge.builder(meterName, valueSupplier, value -> number(value.get()).doubleValue())
                .tags(tags(key))
                .register(meterRegistry);
        return () -> meterRegistry.remove(meter);
    }

    public void recordDuration(ProjectContext project, String resource, String operation, Duration duration, boolean success) {
        if (meterRegistry == null || duration == null) {
            return;
        }
        ResourceKey key = key(project, resource);
        Timer.builder("wiz.project.resource.transaction")
                .tags(tags(key).and("operation", safeTag(operation), "outcome", success ? "success" : "error"))
                .register(meterRegistry)
                .record(duration);
    }

    public Map<String, Object> healthSnapshot() {
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        health.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> snapshot.put(entry.getKey().displayName(), snapshot(entry.getValue())));
        return snapshot;
    }

    boolean hasDownResource() {
        return health.values().stream().anyMatch(supplier -> snapshot(supplier).status() == ProjectResourceHealth.Status.DOWN);
    }

    private ProjectResourceHealth snapshot(Supplier<ProjectResourceHealth> supplier) {
        try {
            ProjectResourceHealth value = supplier.get();
            return value == null ? ProjectResourceHealth.unknown("health supplier returned null") : value;
        } catch (RuntimeException exception) {
            return ProjectResourceHealth.down(exception.getMessage());
        }
    }

    private ResourceKey key(ProjectContext project, String resource) {
        String projectName = project == null ? "unknown" : safeTag(project.name());
        return new ResourceKey(projectName, safeTag(resource));
    }

    private Tags tags(ResourceKey key) {
        return Tags.of("project", key.project(), "resource", key.resource());
    }

    private Number number(Object value) {
        return value instanceof Number number ? number : 0;
    }

    private String metricName(String value) {
        String metric = safeTag(value).replace('-', '.');
        return metric.matches("[A-Za-z0-9_.]+") ? metric : "unknown";
    }

    private String safeTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private record ResourceKey(String project, String resource) implements Comparable<ResourceKey> {

        String displayName() {
            return project + ":" + resource;
        }

        @Override
        public int compareTo(ResourceKey other) {
            int projectCompare = project.compareTo(other.project);
            return projectCompare != 0 ? projectCompare : resource.compareTo(other.resource);
        }
    }
}
