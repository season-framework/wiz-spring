package com.wiz.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.wiz.build.ProjectBuildService;
import com.wiz.config.WizRedirectProperties;
import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.domain.ModelRegistry;
import com.wiz.http.ResponseEnvelope;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.ProjectRegistry;
import com.wiz.runtime.ProjectRuntimeCache;
import com.wiz.runtime.WizRequest;
import com.wiz.runtime.WizResult;
import com.wiz.runtime.WizRuntime;

import jakarta.servlet.SessionCookieConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;

class RouteDispatcherTest {

    @TempDir
    Path tempDir;

    @Test
    void handlesProjectAuthCheckBeforeSpaFallback() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        new ProjectBuildService().build(project, true, "bundle");

        WizResult result;
        try (ProjectRuntimeCache cache = new ProjectRuntimeCache()) {
            result = dispatcher(workspace, cache).dispatch(WizRequest.builder().path("/auth/check").build())
                    .orElseThrow();
        }
        ResponseEnvelope envelope = (ResponseEnvelope) result.entity();

        assertEquals(200, result.httpStatus());
        assertEquals(200, envelope.code());
        assertEquals(Map.of("status", false, "session", Map.of()), envelope.data());
    }

    @Test
    void handlesProjectAuthLogoutAsRedirectAndClearsSessionCookie() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        new ProjectBuildService().build(project, true, "bundle");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("id", "u1");

        WizResult result;
        try (ProjectRuntimeCache cache = new ProjectRuntimeCache()) {
            result = dispatcher(workspace, cache).dispatch(WizRequest.builder().path("/auth/logout")
                    .queryString("returnTo=/dashboard").session(session).build()).orElseThrow();
        }

        assertEquals(302, result.httpStatus());
        assertEquals(List.of("/dashboard"), result.headers().get(HttpHeaders.LOCATION));
        assertTrue(result.headers().get(HttpHeaders.SET_COOKIE).getFirst().startsWith("JSESSIONID=;"));
        assertTrue(session.isInvalid());
    }

    @Test
    void logoutExpiresTheEffectiveServletSessionCookie() throws Exception {
        Path workspace = tempDir.resolve("workspace-custom-cookie");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        new ProjectBuildService().build(project, true, "bundle");
        MockServletContext servletContext = new MockServletContext();
        SessionCookieConfig config = servletContext.getSessionCookieConfig();
        config.setName("WIZSESSION");
        config.setDomain("example.test");
        config.setPath("/app");
        config.setHttpOnly(true);
        config.setSecure(true);
        config.setAttribute("SameSite", "Strict");
        MockHttpSession session = new MockHttpSession(servletContext);
        session.setAttribute("id", "u1");

        WizResult result;
        try (ProjectRuntimeCache cache = new ProjectRuntimeCache()) {
            result = dispatcher(workspace, cache).dispatch(WizRequest.builder()
                    .path("/auth/logout")
                    .session(session)
                    .build()).orElseThrow();
        }

        String cookie = result.headers().get(HttpHeaders.SET_COOKIE).getFirst();
        assertTrue(cookie.startsWith("WIZSESSION=;"));
        assertTrue(cookie.contains("Path=/app"));
        assertTrue(cookie.contains("Domain=example.test"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict"));
    }

    @Test
    void logoutRedirectPolicyDefaultsToAnyForExternalRedirects() throws Exception {
        Path workspace = tempDir.resolve("workspace-any-redirect");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        new ProjectBuildService().build(project, true, "bundle");

        WizResult result;
        try (ProjectRuntimeCache cache = new ProjectRuntimeCache()) {
            result = dispatcher(workspace, cache).dispatch(WizRequest.builder()
                    .path("/auth/logout")
                    .queryString("returnTo=https%3A%2F%2Fexample.com%2Fafter-logout")
                    .build()).orElseThrow();
        }

        assertEquals(302, result.httpStatus());
        assertEquals(List.of("https://example.com/after-logout"), result.headers().get(HttpHeaders.LOCATION));
    }

    @Test
    void logoutRedirectLocalOnlyPolicyFallsBackForExternalRedirects() throws Exception {
        Path workspace = tempDir.resolve("workspace-local-redirect");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        new ProjectBuildService().build(project, true, "bundle");
        WizRedirectProperties redirectProperties = new WizRedirectProperties();
        redirectProperties.setPolicy(WizRedirectProperties.Policy.LOCAL_ONLY);

        WizResult result;
        try (ProjectRuntimeCache cache = new ProjectRuntimeCache()) {
            result = dispatcher(workspace, redirectProperties, cache).dispatch(WizRequest.builder()
                    .path("/auth/logout")
                    .queryString("returnTo=https%3A%2F%2Fexample.com%2Fafter-logout")
                    .build()).orElseThrow();
        }

        assertEquals(302, result.httpStatus());
        assertEquals(List.of("/"), result.headers().get(HttpHeaders.LOCATION));
    }

    @Test
    void handlesAuthenticatedProjectAuthCheck() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        new ProjectBuildService().build(project, true, "bundle");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("id", "u1");
        session.setAttribute("email", "u1@example.com");
        session.setAttribute("name", "User One");
        session.setAttribute("role", "user");

        WizResult result;
        try (ProjectRuntimeCache cache = new ProjectRuntimeCache()) {
            result = dispatcher(workspace, cache).dispatch(WizRequest.builder().path("/auth/check")
                    .session(session).build()).orElseThrow();
        }
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
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        new ProjectBuildService().build(project, true, "bundle");

        try (ProjectRuntimeCache cache = new ProjectRuntimeCache()) {
            assertTrue(dispatcher(workspace, cache).dispatch(WizRequest.builder().path("/dashboard").build()).isEmpty());
        }
    }

    @Test
    void exposesOidcAndSamlAsExplicitExtensionBoundaries() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        new ProjectBuildService().build(project, true, "bundle");

        WizResult oidc;
        WizResult saml;
        try (ProjectRuntimeCache cache = new ProjectRuntimeCache()) {
            RouteDispatcher dispatcher = dispatcher(workspace, cache);
            oidc = dispatcher.dispatch(WizRequest.builder().path("/auth/oidc/login/main/callback").build())
                    .orElseThrow();
            saml = dispatcher.dispatch(WizRequest.builder().path("/auth/saml/login/season/index").build())
                    .orElseThrow();
        }

        assertEquals(501, oidc.httpStatus());
        assertEquals(501, saml.httpStatus());
    }

    @Test
    void dispatchesProjectLocalRouteJavaHandlerByConvention() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        java.nio.file.Files.createDirectories(project.routeRoot().resolve("custom.echo"));
        java.nio.file.Files.writeString(project.routeRoot().resolve("custom.echo/app.json"), "{\"id\":\"custom.echo\",\"route\":\"/echo/<name>\",\"methods\":[\"GET\"]}\n");
        java.nio.file.Files.writeString(project.routeRoot().resolve("custom.echo/route.java"), echoRouteJava());
        new ProjectBuildService().build(project, true, "bundle");

        WizResult result;
        try (ProjectRuntimeCache cache = new ProjectRuntimeCache()) {
            result = dispatcher(workspace, cache).dispatch(WizRequest.builder().path("/echo/alice").build())
                    .orElseThrow();
        }
        ResponseEnvelope envelope = (ResponseEnvelope) result.entity();

        assertEquals(200, result.httpStatus());
        assertEquals(Map.of("name", "alice"), envelope.data());
    }

    private RouteDispatcher dispatcher(Path workspace, ProjectRuntimeCache cache) {
        return dispatcher(workspace, new WizRedirectProperties(), cache);
    }

    private RouteDispatcher dispatcher(Path workspace, WizRedirectProperties redirectProperties,
            ProjectRuntimeCache cache) {
        ProjectRegistry registry = new ProjectRegistry(new PathService(workspace));
        WizRuntime runtime = new WizRuntime(registry, new ModelRegistry(cache), redirectProperties, cache);
        return new RouteDispatcher(
                runtime,
                new RouteRegistry(cache),
                new ControllerChain(cache),
                List.of(),
                cache);
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
