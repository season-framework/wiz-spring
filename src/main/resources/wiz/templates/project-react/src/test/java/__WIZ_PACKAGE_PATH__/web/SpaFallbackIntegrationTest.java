package __WIZ_PACKAGE_ROOT__.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import __WIZ_PACKAGE_ROOT__.Application;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        classes = Application.class,
        properties = {
                "app.api.prefix=/api/v2",
                "spring.datasource.url=jdbc:h2:mem:spa-fallback;DB_CLOSE_DELAY=-1",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.web.resources.static-locations=classpath:/static/"
        })
@AutoConfigureMockMvc
class SpaFallbackIntegrationTest {

    private static final String INDEX_MARKER = "spa-fallback-test-index";

    @Autowired
    private MockMvc mvc;

    @Test
    void servesIndexForMissingHtmlNavigation() throws Exception {
        mvc.perform(get("/dashboard/settings")
                        .accept(MediaType.TEXT_HTML)
                        .header("Sec-Fetch-Mode", "navigate"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(INDEX_MARKER)));
    }

    @Test
    void neverFallsBackForBackendOrStaticNamespaces() throws Exception {
        for (String path : List.of(
                "/api/v2/missing",
                "/v3/api-docs/missing",
                "/swagger-ui/missing",
                "/actuator/missing",
                "/assets/missing.js")) {
            mvc.perform(get(path)
                            .accept(MediaType.TEXT_HTML)
                            .header("Sec-Fetch-Mode", "navigate"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().string(not(containsString(INDEX_MARKER))));
        }

        mvc.perform(get("/app-config.json").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().string(not(containsString(INDEX_MARKER))));
        mvc.perform(get("/app-config.json").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiPrefix").value("/api/v2"))
                .andExpect(content().string(not(containsString(INDEX_MARKER))));
    }

    @Test
    void requiresGetAndAnExplicitHtmlNavigation() throws Exception {
        mvc.perform(post("/dashboard/settings").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString(INDEX_MARKER))));
        mvc.perform(get("/dashboard/settings").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString(INDEX_MARKER))));
        mvc.perform(get("/dashboard/settings").accept(MediaType.ALL))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString(INDEX_MARKER))));
        mvc.perform(get("/dashboard/settings")
                        .accept(MediaType.TEXT_HTML)
                        .header("Sec-Fetch-Mode", "cors"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString(INDEX_MARKER))));
    }

    @Test
    void preservesAnExistingStaticResource() throws Exception {
        mvc.perform(get("/spa-test.js").accept("application/javascript"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("static-resource-wins")))
                .andExpect(content().string(not(containsString(INDEX_MARKER))));
    }
}
