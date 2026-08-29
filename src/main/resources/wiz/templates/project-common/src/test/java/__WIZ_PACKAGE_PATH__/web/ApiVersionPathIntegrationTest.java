package __WIZ_PACKAGE_ROOT__.web;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import __WIZ_PACKAGE_ROOT__.config.ApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiVersionPathIntegrationTest.VersionedController.class)
@TestPropertySource(properties = {
        "app.api.prefix=/api",
        "app.api.versioning.mode=path",
        "app.api.versioning.default-version=2",
        "app.api.versioning.supported-versions[0]=2",
        "spring.datasource.url=jdbc:h2:mem:api-version-path;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ApiVersionPathIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void resolvesVersionFromTheCentralPathSegment() throws Exception {
        mvc.perform(get("/api/2/versioned")).andExpect(status().isOk());
        mvc.perform(get("/api/2/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/9/versioned")).andExpect(status().isBadRequest());
    }

    @Test
    void leavesNonApiEndpointsOutsidePathVersionResolution() throws Exception {
        mvc.perform(get("/app-config.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiPrefix").value("/api/2"));
    }

    @Test
    void documentsTheRequiredVersionPathParameter() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/{version}/dashboard'].get.parameters[0].name")
                        .value("version"))
                .andExpect(jsonPath("$.paths['/api/{version}/dashboard'].get.parameters[0].in")
                        .value("path"))
                .andExpect(jsonPath("$.paths['/api/{version}/dashboard'].get.parameters[0].required")
                        .value(true));
    }

    @Test
    void pathVersioningRequiresAClientDefaultVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> new ApiProperties.Versioning("path", null, List.of()));
    }

    @ApiController(path = "/versioned", version = "2")
    public static class VersionedController {
        @GetMapping
        Map<String, Integer> version() {
            return Map.of("version", 2);
        }
    }
}
