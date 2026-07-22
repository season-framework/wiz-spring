package com.wiz.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicInteger;

import com.wiz.config.WizApiProperties;
import com.wiz.dispatch.RouteRegistry;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;

class SwaggerUiResourceHandlerMappingTest {

    @TempDir
    java.nio.file.Path tempDir;

    @Test
    void delegatesOnlyConfiguredSwaggerAssetsAndAccountsForContextPath() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HandlerExecutionChain expected = new HandlerExecutionChain(new Object());
        HandlerMapping delegate = request -> {
            calls.incrementAndGet();
            return expected;
        };
        SwaggerUiResourceHandlerMapping mapping = new SwaggerUiResourceHandlerMapping(delegate, "/docs/openapi.html");
        MockHttpServletRequest swagger = new MockHttpServletRequest("GET", "/portal/docs/swagger-ui/index.html");
        swagger.setContextPath("/portal");

        assertEquals(expected, mapping.getHandler(swagger));
        assertEquals(1, calls.get());

        MockHttpServletRequest app = new MockHttpServletRequest("GET", "/portal/docs/application");
        app.setContextPath("/portal");
        assertNull(mapping.getHandler(app));
        assertEquals(1, calls.get());
    }

    @Test
    void doesNotCreateServletHandlerMappingInANonWebContext() {
        new ApplicationContextRunner()
                .withUserConfiguration(WizOpenApiConfiguration.class)
                .withBean(WizApiProperties.class, WizApiProperties::new)
                .withBean(ProjectRegistry.class, () -> new ProjectRegistry(new PathService(tempDir)))
                .withBean(RouteRegistry.class, RouteRegistry::new)
                .run(context -> assertFalse(context.containsBean("wizSwaggerUiResourceHandlerMapping")));
    }
}
