package com.wiz.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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
import org.springframework.mock.web.MockHttpSession;

class ControllerChainTest {

    @TempDir
    Path tempDir;

    @Test
    void baseControllerAddsSessionBootstrapData() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("id", "u1");
        session.setAttribute("name", "User One");

        try (WizContext context = new WizContext(
                WizRequest.builder().session(session).build(),
                new WizResponse(),
                project)) {
            assertTrue(new ControllerChain().before(context, Map.of("controller", "base")).isEmpty());

            assertEquals(Map.of("id", "u1", "name", "User One"), context.response().data().get("session"));
        }
    }

    @Test
    void portalSeasonBaseMapsToBuiltInBaseController() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("id", "u1");

        try (WizContext context = new WizContext(
                WizRequest.builder().session(session).build(),
                new WizResponse(),
                project)) {
            assertTrue(new ControllerChain().before(context, Map.of("controller", "portal/season/base")).isEmpty());

            assertEquals(Map.of("id", "u1"), context.response().data().get("session"));
        }
    }

    @Test
    void userAndAdminControllersExpandBuiltInGuardChain() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        ControllerChain chain = new ControllerChain();

        try (WizContext anonymous = new WizContext(WizRequest.builder().session(new MockHttpSession()).build(), new WizResponse(), project)) {
            assertEquals(401, chain.before(anonymous, Map.of("controller", "user")).orElseThrow().httpStatus());
            assertEquals(Map.of(), anonymous.response().data().get("session"));
        }

        MockHttpSession userSession = new MockHttpSession();
        userSession.setAttribute("id", "u1");
        userSession.setAttribute("role", "user");
        try (WizContext user = new WizContext(WizRequest.builder().session(userSession).build(), new WizResponse(), project)) {
            assertEquals(401, chain.before(user, Map.of("controller", "admin")).orElseThrow().httpStatus());
            assertEquals(Map.of("id", "u1", "role", "user"), user.response().data().get("session"));
        }

        MockHttpSession adminSession = new MockHttpSession();
        adminSession.setAttribute("id", "u1");
        adminSession.setAttribute("role", "admin");
        try (WizContext admin = new WizContext(WizRequest.builder().session(adminSession).build(), new WizResponse(), project)) {
            assertTrue(chain.before(admin, Map.of("controller", "admin")).isEmpty());
        }
    }
}