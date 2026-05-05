package com.wiz.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.domain.ModelRegistry;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizRequest;
import com.wiz.runtime.WizResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;

class SessionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void storesValuesInHttpSession() {
        MockHttpSession httpSession = new MockHttpSession();
        SessionService session = new SessionService(httpSession);

        session.set(Map.of("id", "u1", "email", "u1@example.com", "role", "admin"));

        assertTrue(session.has("id"));
        assertEquals("u1", session.get("id").orElseThrow());
        assertEquals("admin", httpSession.getAttribute("role"));
        assertEquals("u1@example.com", session.toMap().get("email"));
        assertEquals("u1", session.userId().orElseThrow());

        session.delete("email");
        assertFalse(session.has("email"));
        session.clear();
        assertTrue(session.toMap().isEmpty());
    }

    @Test
    void exposesSessionThroughModelProvider() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        MockHttpSession httpSession = new MockHttpSession();
        ModelRegistry models = new ModelRegistry(List.of(new SessionModelProvider()));

        try (WizContext context = new WizContext(
                WizRequest.builder().session(httpSession).build(),
                new WizResponse(),
                project,
                models)) {
            SessionService session = context.models().get("portal/season/session", SessionService.class);

            session.set("id", "u1");
            assertSame(context.session(), session);
            assertEquals("u1", httpSession.getAttribute("id"));
        }
    }
}