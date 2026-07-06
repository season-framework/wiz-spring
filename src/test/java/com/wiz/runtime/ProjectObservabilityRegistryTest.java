package com.wiz.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

class ProjectObservabilityRegistryTest {

    @Test
    void exposesProjectHealthAndRemovesRegistrationOnClose() throws Exception {
        ProjectContext project = project();
        ProjectObservabilityRegistry registry = new ProjectObservabilityRegistry(new SimpleMeterRegistry());

        AutoCloseable registration = registry.registerHealth(project, "sample.jpa", () -> ProjectResourceHealth.up(Map.of("pool", "open")));

        assertTrue(registry.healthSnapshot().containsKey("main:sample.jpa"));
        assertEquals(false, registry.hasDownResource());

        registration.close();

        assertTrue(registry.healthSnapshot().isEmpty());
    }

    @Test
    void registersGaugeAndTransactionTimer() throws Exception {
        ProjectContext project = project();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ProjectObservabilityRegistry registry = new ProjectObservabilityRegistry(meterRegistry);
        AtomicInteger active = new AtomicInteger(3);

        AutoCloseable gauge = registry.registerGauge(project, "sample.jpa", "pool.active", active::get);
        registry.recordDuration(project, "sample.jpa", "transaction", Duration.ofMillis(12), true);

        assertEquals(3.0, meterRegistry.get("wiz.project.resource.pool.active").tag("project", "main").tag("resource", "sample.jpa").gauge().value());
        assertEquals(1, meterRegistry.get("wiz.project.resource.transaction").tag("operation", "transaction").tag("outcome", "success").timer().count());

        gauge.close();

        assertNull(meterRegistry.find("wiz.project.resource.pool.active").gauge());
    }

    private ProjectContext project() {
        Path root = Path.of("/tmp/main");
        return new ProjectContext(
                "main",
                "com.wiz.app",
                root,
                root.resolve("src"),
                root.resolve("src/app"),
                root.resolve("src/model"),
                root.resolve("src/route"),
                root.resolve("src/assets"),
                root.resolve("config"),
                root.resolve("build"),
                root.resolve("bundle"));
    }
}
