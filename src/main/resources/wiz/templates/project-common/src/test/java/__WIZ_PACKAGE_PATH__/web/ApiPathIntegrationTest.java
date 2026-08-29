package __WIZ_PACKAGE_ROOT__.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.api.prefix=/api/v2",
        "spring.datasource.url=jdbc:h2:mem:api-path;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ApiPathIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void appliesConfiguredPrefixWithoutControllerPlaceholder() throws Exception {
        mvc.perform(get("/api/v2/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/dashboard")).andExpect(status().isNotFound());
    }
}
