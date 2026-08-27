package __WIZ_PACKAGE_ROOT__.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.api.prefix=/api",
        "spring.datasource.url=jdbc:h2:mem:sample-api;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class SampleApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void runsTheSeededLoginAndMainApiFlow() throws Exception {
        mvc.perform(get("/api/dashboard"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        MvcResult login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"admin@example.com","password":"admin1234"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.role").value("admin"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@example.com"));

        mvc.perform(get("/api/dashboard").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.length()").value(4))
                .andExpect(jsonPath("$.stats[0].value").value(3))
                .andExpect(jsonPath("$.recent.length()").value(3));

        mvc.perform(get("/api/members").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].password").doesNotExist());

        MvcResult invite = mvc.perform(post("/api/members")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"eve@example.com","name":"Eve","role":"viewer"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("eve@example.com"))
                .andReturn();
        String memberId = stringValue(invite, "id");

        MvcResult memberLogin = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"eve@example.com","password":"welcome1"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession memberSession = (MockHttpSession) memberLogin.getRequest().getSession(false);

        mvc.perform(get("/api/members/{id}", memberId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Eve"));

        mvc.perform(get("/api/posts/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").isString());

        mvc.perform(get("/api/posts").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        mvc.perform(get("/api/posts").param("text", "중앙 API prefix"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].title").value("API 작성 가이드"));

        MvcResult createPost = mvc.perform(post("/api/posts")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"Integration Post",
                                  "content":"Created by the generated sample test",
                                  "category":"공지사항",
                                  "status":"draft"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorName").value("관리자"))
                .andReturn();
        String postId = stringValue(createPost, "id");

        mvc.perform(put("/api/posts/{id}", postId)
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"Integration Post Updated",
                                  "content":"Updated content",
                                  "category":"가이드",
                                  "status":"published"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("published"));

        mvc.perform(get("/api/profile").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@example.com"));

        mvc.perform(put("/api/profile")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Admin Updated","mobile":"010-9999-0000"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Admin Updated"));

        mvc.perform(post("/api/chat/messages")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"hello sample chat"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorName").value("Admin Updated"));

        mvc.perform(get("/api/chat/messages").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].text").value("hello sample chat"));

        mvc.perform(put("/api/profile/password")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"admin1234","newPassword":"admin4321"}
                                """))
                .andExpect(status().isNoContent());

        mvc.perform(delete("/api/posts/{id}", postId).session(session))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/members/{id}", memberId).session(session))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/dashboard").session(memberSession))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"admin@example.com","password":"admin1234"}
                                """))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"admin@example.com","password":"admin4321"}
                                """))
                .andExpect(status().isOk());
    }

    private String stringValue(MvcResult result, String key) throws Exception {
        Map<String, Object> body = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<Map<String, Object>>() {
                });
        return body.get(key).toString();
    }
}
