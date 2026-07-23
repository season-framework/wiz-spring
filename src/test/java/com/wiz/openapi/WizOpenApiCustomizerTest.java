package com.wiz.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.wiz.config.WizApiProperties;
import com.wiz.dispatch.RouteRegistry;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectRegistry;
import com.wiz.runtime.ProjectRuntimeCache;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WizOpenApiCustomizerTest {

    @TempDir
    Path tempDir;

    @Test
    void documentsConfiguredAppApiPrefixAndDynamicWizRoutes() throws Exception {
        writeRoute("auth", """
                {
                  "id": "auth",
                  "title": "Authentication callback",
                  "route": "/auth/<path:path>",
                  "controller": "user",
                  "methods": ["GET", "POST", "DELETE"]
                }
                """);
        WizApiProperties apiProperties = new WizApiProperties();
        apiProperties.setPrefix("/internal/api");
        OpenAPI openApi = new OpenAPI();

        try (ProjectRuntimeCache cache = new ProjectRuntimeCache()) {
            customizer(apiProperties, cache).customise(openApi);

            assertNotNull(openApi.getPaths().get("/internal/api/{appId}/{function}").getGet());
            assertNotNull(openApi.getPaths().get("/internal/api/{appId}/{function}/{path}").getPost());
            PathItem route = openApi.getPaths().get("/auth/{path}");
            assertNotNull(route);
            assertNotNull(route.getGet());
            assertNotNull(route.getPost());
            assertNull(route.getDelete(), "DELETE is not dispatched by the runtime catch-all controller");

            Operation get = route.getGet();
            assertEquals("Authentication callback", get.getSummary());
            assertEquals("auth", get.getExtensions().get("x-wiz-route-id"));
            assertEquals("user", get.getExtensions().get("x-wiz-controller"));
            assertEquals("path", get.getParameters().getFirst().getName());
            assertEquals("path", get.getParameters().getFirst().getExtensions().get("x-wiz-segment-type"));
            assertTrue(get.getResponses().containsKey("default"));
        }
    }

    @Test
    void defaultsLegacyRoutesToGetAndPostAndSkipsMalformedParameters() throws Exception {
        writeRoute("status", """
                {
                  "id": "status",
                  "route": "/status/<id>"
                }
                """);
        writeRoute("invalid", """
                {
                  "id": "invalid",
                  "route": "/invalid/<path:bad-name>"
                }
                """);
        OpenAPI openApi = new OpenAPI();

        try (ProjectRuntimeCache cache = new ProjectRuntimeCache()) {
            customizer(new WizApiProperties(), cache).customise(openApi);

            PathItem status = openApi.getPaths().get("/status/{id}");
            assertNotNull(status.getGet());
            assertNotNull(status.getPost());
            assertFalse(openApi.getPaths().keySet().stream().anyMatch(path -> path.startsWith("/invalid")));
        }
    }

    @Test
    void preservesAnExistingSpringOperationOnAPathCollision() throws Exception {
        writeRoute("smoke", """
                {
                  "id": "smoke",
                  "route": "/smoke",
                  "methods": ["GET", "POST"]
                }
                """);
        Operation existing = new Operation().operationId("springSmoke");
        OpenAPI openApi = new OpenAPI().path("/smoke", new PathItem().get(existing));

        try (ProjectRuntimeCache cache = new ProjectRuntimeCache()) {
            customizer(new WizApiProperties(), cache).customise(openApi);

            assertEquals(existing, openApi.getPaths().get("/smoke").getGet());
            assertNotNull(openApi.getPaths().get("/smoke").getPost());
        }
    }

    private WizOpenApiCustomizer customizer(WizApiProperties apiProperties, ProjectRuntimeCache cache) {
        PathService paths = new PathService(tempDir);
        return new WizOpenApiCustomizer(new ProjectRegistry(paths), new RouteRegistry(cache), apiProperties);
    }

    private void writeRoute(String id, String metadata) throws Exception {
        Path route = tempDir.resolve("bundle/src/route").resolve(id);
        Files.createDirectories(route);
        Files.writeString(route.resolve("app.json"), metadata);
    }
}
