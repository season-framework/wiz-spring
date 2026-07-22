package com.wiz.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WizOpenApiIntegrationTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Test
    void servesOpenApiDescriptionWithoutCatchAllOrSocketInternals() throws Exception {
        HttpResponse<String> response = get("/v3/api-docs");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("application/json"));
        assertTrue(response.body().contains("\"title\":\"WIZ Spring API\""));
        assertTrue(response.body().contains("/wiz/api/{appId}/{function}"));
        assertTrue(response.body().contains("/smoke"));
        assertFalse(response.body().contains("\"/**\""));
        assertFalse(response.body().contains("/socket.io"));
    }

    @Test
    void servesSwaggerUiAssetsAheadOfTheSpaFallback() throws Exception {
        HttpResponse<String> response = get("/swagger-ui.html");

        assertEquals(200, response.statusCode());
        assertTrue(response.uri().getPath().startsWith("/swagger-ui/"));
        assertTrue(response.body().contains("Swagger UI"));

        HttpResponse<String> configuration = get("/v3/api-docs/swagger-config");
        assertEquals(200, configuration.statusCode());
        assertTrue(configuration.body().contains("\"supportedSubmitMethods\":[]"));
        assertFalse(configuration.body().contains("https://petstore.swagger.io"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
