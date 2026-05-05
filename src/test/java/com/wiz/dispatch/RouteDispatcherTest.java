package com.wiz.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.wiz.build.ProjectBuildService;
import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.http.ResponseEnvelope;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.ProjectRegistry;
import com.wiz.runtime.WizRequest;
import com.wiz.runtime.WizResult;
import com.wiz.runtime.WizRuntime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;

class RouteDispatcherTest {

    @TempDir
    Path tempDir;

    @Test
    void handlesCoreAuthCheckBeforeSpaFallback() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        new ProjectBuildService().build(project, true, "bundle");

        WizResult result = dispatcher(workspace).dispatch(WizRequest.builder().path("/auth/check").build()).orElseThrow();
        ResponseEnvelope envelope = (ResponseEnvelope) result.entity();

        assertEquals(200, result.httpStatus());
        assertEquals(200, envelope.code());
        assertEquals(Map.of("status", false, "session", Map.of()), envelope.data());
    }

    @Test
    void handlesCoreAuthLogoutAsRedirectAndClearsSessionCookie() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        new ProjectBuildService().build(project, true, "bundle");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("id", "u1");

        WizResult result = dispatcher(workspace).dispatch(WizRequest.builder().path("/auth/logout").queryString("returnTo=/dashboard").session(session).build()).orElseThrow();

        assertEquals(302, result.httpStatus());
        assertEquals(List.of("/dashboard"), result.headers().get(HttpHeaders.LOCATION));
        assertTrue(result.headers().get(HttpHeaders.SET_COOKIE).getFirst().startsWith("JSESSIONID=;"));
        assertTrue(session.isInvalid());
    }

    @Test
    void handlesAuthenticatedCoreAuthCheck() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        new ProjectBuildService().build(project, true, "bundle");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("id", "u1");
        session.setAttribute("email", "u1@example.com");
        session.setAttribute("name", "User One");
        session.setAttribute("role", "user");

        WizResult result = dispatcher(workspace).dispatch(WizRequest.builder().path("/auth/check").session(session).build()).orElseThrow();
        ResponseEnvelope envelope = (ResponseEnvelope) result.entity();

        assertEquals(200, result.httpStatus());
        assertEquals(Map.of(
                "status", true,
                "session", Map.of("id", "u1", "email", "u1@example.com", "name", "User One", "role", "user")), envelope.data());
    }

    @Test
    void returnsEmptyForNonRoutePaths() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        new ProjectBuildService().build(project, true, "bundle");

        assertTrue(dispatcher(workspace).dispatch(WizRequest.builder().path("/dashboard").build()).isEmpty());
    }

    @Test
    void exposesOidcAndSamlAsExplicitExtensionBoundaries() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        new ProjectBuildService().build(project, true, "bundle");

        WizResult oidc = dispatcher(workspace).dispatch(WizRequest.builder().path("/auth/oidc/login/main/callback").build()).orElseThrow();
        WizResult saml = dispatcher(workspace).dispatch(WizRequest.builder().path("/auth/saml/login/season/index").build()).orElseThrow();

        assertEquals(501, oidc.httpStatus());
        assertEquals(501, saml.httpStatus());
    }

    @Test
    void dispatchesProjectLocalRouteJavaHandlerByConvention() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        java.nio.file.Files.createDirectories(project.routeRoot().resolve("custom.echo"));
        java.nio.file.Files.writeString(project.routeRoot().resolve("custom.echo/app.json"), "{\"id\":\"custom.echo\",\"route\":\"/echo/<name>\",\"methods\":[\"GET\"]}\n");
        java.nio.file.Files.writeString(project.routeRoot().resolve("custom.echo/route.java"), echoRouteJava());
        new ProjectBuildService().build(project, true, "bundle");

        WizResult result = dispatcher(workspace).dispatch(WizRequest.builder().path("/echo/alice").build()).orElseThrow();
        ResponseEnvelope envelope = (ResponseEnvelope) result.entity();

        assertEquals(200, result.httpStatus());
        assertEquals(Map.of("name", "alice"), envelope.data());
    }

    private RouteDispatcher dispatcher(Path workspace) {
        ProjectRegistry registry = new ProjectRegistry(new PathService(workspace));
        return new RouteDispatcher(
                new WizRuntime(registry),
                new RouteRegistry(),
                new ControllerChain(),
                List.of(new AuthRouteHandler()));
    }

    private String echoRouteJava() {
        return "import com.wiz.dispatch.RouteHandler;\n"
                + "import com.wiz.runtime.WizContext;\n"
                + "import com.wiz.runtime.WizResult;\n"
                + "import com.wiz.runtime.WizSegment;\n"
                + "import java.util.Map;\n\n"
                + "public final class CustomEchoRouteHandler implements RouteHandler {\n"
                + "    public String routeId() { return \"custom.echo\"; }\n"
                + "    public WizResult handle(WizContext context, WizSegment segment) {\n"
                + "        return context.response().ok(Map.of(\"name\", segment.require(\"name\")));\n"
                + "    }\n"
                + "}\n";
    }
}
