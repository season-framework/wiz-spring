package com.wiz.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizRequest;
import com.wiz.runtime.WizResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void supportsConfiguredSqliteCrudRowsCountUpsertAndDtoSanitizing() throws Exception {
        ProjectContext project = createProject();
        Files.writeString(project.configRoot().resolve("database.yml"), "base: data/base-test.sqlite\npost: data/post-test.sqlite\n");
        OrmModel users = new OrmService(project).use("user");
        String password = PasswordHasher.hash("secret");

        String anaId = users.insert(user("ana@example.com", password, "Ana", "user", "2026-05-05 10:00:00"));
        String boraId = users.insert(user("bora@example.com", password, "Bora", "admin", "2026-05-05 11:00:00"));
        users.insert(user("carl@example.com", password, "Carl", "user", "2026-05-05 12:00:00"));

        Map<String, Object> ana = users.get("id", anaId);
        List<Map<String, Object>> searchRows = users.rows(RowsQuery.builder()
                .fields("id,name,role")
                .like("name")
                .where("name", "ar")
                .orderBy("created")
                .order("DESC")
                .build());
        List<Map<String, Object>> secondPage = users.rows(RowsQuery.builder()
                .page(2)
                .dump(1)
                .orderBy("created")
                .order("ASC")
                .build());

        assertTrue(Files.exists(project.root().resolve("data/base-test.sqlite")));
        assertEquals(32, anaId.length());
        assertTrue(PasswordHasher.matches("secret", ana.get("password").toString()));
        assertEquals(2, users.count(Map.of("role", "user")));
        assertEquals(List.of("Carl"), searchRows.stream().map(row -> row.get("name")).toList());
        assertEquals("Bora", secondPage.getFirst().get("name"));
        assertFalse(users.toDto(ana).containsKey("password"));

        assertEquals(1, users.update(Map.of("mobile", "010-0000"), Map.of("id", anaId)));
        assertEquals("010-0000", users.get("id", anaId).get("mobile"));
        assertEquals(anaId, users.upsert(userWithId(anaId, "ana@example.com", password, "Ana Updated", "user", "2026-05-05 13:00:00"), "id"));
        assertEquals("Ana Updated", users.get("id", anaId).get("name"));
        assertEquals(1, users.delete(Map.of("id", boraId)));
        assertEquals(2, users.count(Map.of()));
    }

    @Test
    void exposesOrmServiceFromWizContext() throws Exception {
        ProjectContext project = createProject();

        try (WizContext context = new WizContext(WizRequest.builder().build(), new WizResponse(), project)) {
            assertNotNull(context.orm().use("user"));
        }
    }

    private ProjectContext createProject() throws Exception {
        Path workspace = tempDir.resolve("workspace-" + java.util.UUID.randomUUID());
        new WorkspaceService().createWorkspace(workspace);
        return new ProjectService(new PathService(workspace)).createProject("main", null, null);
    }

    private Map<String, Object> user(String email, String password, String name, String role, String timestamp) {
        return Map.of(
                "email", email,
                "password", password,
                "name", name,
                "role", role,
                "created", timestamp,
                "updated", timestamp);
    }

    private Map<String, Object> userWithId(String id, String email, String password, String name, String role, String timestamp) {
        return Map.of(
                "id", id,
                "email", email,
                "password", password,
                "name", name,
                "role", role,
                "created", timestamp,
                "updated", timestamp);
    }
}