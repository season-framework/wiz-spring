package com.wiz.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;

import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void autoCreatesUserPostAndCommentSchemasAndReadsSeasonDatabasePath() throws Exception {
        ProjectContext project = createProject();
        Files.deleteIfExists(project.configRoot().resolve("database.yml"));
        Files.writeString(project.configRoot().resolve("season.yml"), "auth_baseuri: /auth\npost:\n  path: data/content.sqlite\n");
        OrmService orm = new OrmService(project);

        OrmModel users = orm.use("user");
        OrmModel posts = orm.use("post");
        OrmModel comments = orm.use("comment");
        String userId = users.insert(Map.of(
                "email", "writer@example.com",
                "password", PasswordHasher.hash("secret"),
                "name", "Writer",
                "role", "admin",
                "created", "2026-05-05 10:00:00",
                "updated", "2026-05-05 10:00:00"));
        String postId = posts.insert(Map.of(
                "title", "Hello",
                "content", "First post",
                "category", "notice",
                "author_id", userId,
                "author_name", "Writer",
                "status", "published",
                "created", "2026-05-05 10:10:00",
                "updated", "2026-05-05 10:10:00"));
        comments.insert(Map.of(
                "post_id", postId,
                "author_id", userId,
                "author_name", "Writer",
                "content", "Nice",
                "created", "2026-05-05 10:20:00"));

        assertEquals(project.root().resolve("data/content.sqlite"), posts.databasePath());
        assertEquals(posts.databasePath(), comments.databasePath());
        assertTrue(tableExists(users.databasePath(), "user"));
        assertTrue(tableExists(posts.databasePath(), "post"));
        assertTrue(tableExists(comments.databasePath(), "comment"));
        assertEquals(1, posts.count(Map.of("status", "published")));
        assertEquals(1, comments.count(Map.of("post_id", postId)));
    }

    private boolean tableExists(Path databasePath, String tableName) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
                var statement = connection.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, tableName);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private ProjectContext createProject() throws Exception {
        Path workspace = tempDir.resolve("workspace-" + java.util.UUID.randomUUID());
        new WorkspaceService().createWorkspace(workspace);
        return new ProjectService(new PathService(workspace)).createProject("main", null, null);
    }
}