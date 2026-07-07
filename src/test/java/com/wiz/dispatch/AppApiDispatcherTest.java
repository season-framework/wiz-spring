package com.wiz.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.mock.web.MockHttpSession;

class AppApiDispatcherTest {

    @TempDir
    Path tempDir;

    @Test
    void invokesCompiledAppLocalJavaApi() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        setDashboardController(project, "base");
        Files.writeString(project.appRoot().resolve("page.dashboard/api.java"), simpleOverviewApi());
        new ProjectBuildService().build(project, true, "bundle");
        AppApiDispatcher dispatcher = dispatcher(workspace);

        WizResult result = dispatcher.dispatch(WizRequest.builder().method("POST").path("/wiz/api/page.dashboard/overview").build(), "page.dashboard", "overview", "");

        ResponseEnvelope envelope = (ResponseEnvelope) result.entity();
        assertEquals(200, result.httpStatus());
        assertEquals(200, envelope.code());
        assertEquals(Map.of("message", "Java WIZ app ready"), envelope.data());
    }

    @Test
    void returnsEnvelopeForMissingFunction() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        setDashboardController(project, "base");
        new ProjectBuildService().build(project, true, "bundle");

        WizResult result = dispatcher(workspace).dispatch(WizRequest.builder().build(), "page.dashboard", "missing", "");

        ResponseEnvelope envelope = (ResponseEnvelope) result.entity();
        assertEquals(404, result.httpStatus());
        assertEquals(404, envelope.code());
        assertEquals(Map.of("error", "function not found"), envelope.data());
    }

    @Test
    void mapsRequiredQueryFailureToBadRequestEnvelope() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        setDashboardController(project, "base");
        Files.writeString(project.appRoot().resolve("page.dashboard/api.java"), requiredQueryApi());
        new ProjectBuildService().build(project, true, "bundle");

        WizResult missing = dispatcher(workspace).dispatch(WizRequest.builder().method("POST").build(), "page.dashboard", "required", "");
        ResponseEnvelope missingEnvelope = (ResponseEnvelope) missing.entity();
        WizResult present = dispatcher(workspace).dispatch(WizRequest.builder().method("POST").queryString("value=ok").build(), "page.dashboard", "required", "");
        ResponseEnvelope presentEnvelope = (ResponseEnvelope) present.entity();

        assertEquals(400, missing.httpStatus());
        assertEquals(400, missingEnvelope.code());
        assertEquals(Map.of("error", "missing required query value", "name", "value"), missingEnvelope.data());
        assertEquals(200, present.httpStatus());
        assertEquals(200, presentEnvelope.code());
        assertEquals("ok", presentEnvelope.data());
    }

    @Test
    void appliesControllerHookBeforeAppApiHandler() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        setDashboardController(project, "guard");
        Files.writeString(project.sourceRoot().resolve("controller/GuardController.java"), guardControllerApi());
        new ProjectBuildService().build(project, true, "bundle");

        WizResult result = dispatcher(workspace).dispatch(WizRequest.builder().method("POST").build(), "page.dashboard", "overview", "");
        ResponseEnvelope envelope = (ResponseEnvelope) result.entity();

        assertEquals(401, result.httpStatus());
        assertEquals(401, envelope.code());
        assertEquals(Map.of("error", "blocked"), envelope.data());
    }

    @Test
    void appliesBaseControllerWhenAppControllerIsBlank() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        setDashboardController(project, "");
        Files.writeString(project.sourceRoot().resolve("controller/BaseController.java"), baseControllerApi());
        new ProjectBuildService().build(project, true, "bundle");

        WizResult result = dispatcher(workspace).dispatch(WizRequest.builder().method("POST").build(), "page.dashboard", "overview", "");
        ResponseEnvelope envelope = (ResponseEnvelope) result.entity();

        assertEquals(409, result.httpStatus());
        assertEquals(409, envelope.code());
        assertEquals(Map.of("error", "base-default"), envelope.data());
    }

    @Test
    void appApiCanResolveProjectModelsFromWizContext() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        setDashboardController(project, "base");
        removeJavaSources(project);
        Files.writeString(project.modelRoot().resolve("Struct.java"), "public final class Struct { public String name() { return \"root\"; } }\n");
        Files.createDirectories(project.modelRoot().resolve("struct"));
        Files.writeString(project.modelRoot().resolve("struct/UserStruct.java"), "public final class UserStruct { public String role() { return \"member\"; } }\n");
        Files.writeString(project.appRoot().resolve("page.dashboard/api.java"), modelApi());
        new ProjectBuildService().build(project, true, "bundle");

        WizResult result = dispatcher(workspace).dispatch(WizRequest.builder().method("POST").build(), "page.dashboard", "models", "");
        ResponseEnvelope envelope = (ResponseEnvelope) result.entity();

        assertEquals(200, result.httpStatus());
        assertEquals(200, envelope.code());
        assertEquals(Map.of("root", "root", "role", "member"), envelope.data());
    }

    @Test
    void appApiCanStoreLoginDataInHttpSession() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        setDashboardController(project, "base");
        Files.writeString(project.appRoot().resolve("page.dashboard/api.java"), loginApi());
        new ProjectBuildService().build(project, true, "bundle");
        MockHttpSession session = new MockHttpSession();

        WizResult result = dispatcher(workspace).dispatch(WizRequest.builder().method("POST").session(session).build(), "page.dashboard", "login", "");
        ResponseEnvelope envelope = (ResponseEnvelope) result.entity();

        assertEquals(200, result.httpStatus());
        assertEquals(200, envelope.code());
        assertEquals("u1", session.getAttribute("id"));
        assertEquals("u1@example.com", session.getAttribute("email"));
        assertEquals("User One", session.getAttribute("name"));
        assertEquals("admin", session.getAttribute("role"));
    }

    @Test
    void builtInUserAndAdminControllerGuardsValidateSession() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        setDashboardController(project, "user");
        new ProjectBuildService().build(project, true, "bundle");

        WizResult anonymous = dispatcher(workspace).dispatch(WizRequest.builder().method("POST").build(), "page.dashboard", "overview", "");
        MockHttpSession userSession = new MockHttpSession();
        userSession.setAttribute("id", "u1");
        WizResult user = dispatcher(workspace).dispatch(WizRequest.builder().method("POST").session(userSession).build(), "page.dashboard", "overview", "");

        setDashboardController(project, "admin");
        new ProjectBuildService().build(project, true, "bundle");
        WizResult nonAdmin = dispatcher(workspace).dispatch(WizRequest.builder().method("POST").session(userSession).build(), "page.dashboard", "overview", "");
        userSession.setAttribute("role", "admin");
        WizResult admin = dispatcher(workspace).dispatch(WizRequest.builder().method("POST").session(userSession).build(), "page.dashboard", "overview", "");

        assertEquals(401, anonymous.httpStatus());
        assertEquals(200, user.httpStatus());
        assertEquals(401, nonAdmin.httpStatus());
        assertEquals(200, admin.httpStatus());
    }

    private AppApiDispatcher dispatcher(Path workspace) {
        ProjectRegistry registry = new ProjectRegistry(new PathService(workspace));
        return new AppApiDispatcher(new WizRuntime(registry));
    }

    private void setDashboardController(ProjectContext project, String controller) throws IOException {
        Path appJson = project.appRoot().resolve("page.dashboard/app.json");
        String metadata = Files.readString(appJson).replaceAll("\\\"controller\\\"\\s*:\\s*\\\"[^\\\"]*\\\"", "\\\"controller\\\": \\\"" + controller + "\\\"");
        Files.writeString(appJson, metadata);
    }

    private void removeJavaSources(ProjectContext project) throws IOException {
        try (var paths = Files.walk(project.sourceRoot())) {
            for (Path source : paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java")).toList()) {
                Files.delete(source);
            }
        }
    }

    private String simpleOverviewApi() {
        return "import java.util.Map;\n\n"
                + "public final class PageDashboardApi {\n"
                + "    public Object overview() {\n"
                + "        return Map.of(\"message\", \"Java WIZ app ready\");\n"
                + "    }\n"
                + "}\n";
    }

    private String requiredQueryApi() {
        return "import com.wiz.runtime.WizContext;\n\n"
                + "public final class PageDashboardApi {\n"
                + "    public Object required(WizContext wiz) {\n"
                + "        return wiz.request().queryRequired(\"value\");\n"
                + "    }\n"
                + "}\n";
    }

    private String guardControllerApi() {
        return "import com.wiz.dispatch.ControllerHook;\n"
                + "import com.wiz.runtime.WizContext;\n"
                + "import com.wiz.runtime.WizResult;\n"
                + "import java.util.Map;\n\n"
                + "public final class GuardController implements ControllerHook {\n"
                + "    public WizResult before(WizContext wiz) {\n"
                + "        return wiz.response().status(401, Map.of(\"error\", \"blocked\"));\n"
                + "    }\n"
                + "}\n";
    }

    private String baseControllerApi() {
        return "import com.wiz.dispatch.ControllerHook;\n"
                + "import com.wiz.runtime.WizContext;\n"
                + "import com.wiz.runtime.WizResult;\n"
                + "import java.util.Map;\n\n"
                + "public final class BaseController implements ControllerHook {\n"
                + "    public WizResult before(WizContext wiz) {\n"
                + "        return wiz.response().status(409, Map.of(\"error\", \"base-default\"));\n"
                + "    }\n"
                + "}\n";
    }

    private String modelApi() {
        return "import com.wiz.runtime.WizContext;\n"
                + "import com.wiz.app.application.model.Struct;\n"
                + "import com.wiz.app.application.service.UserStruct;\n"
                + "import java.util.Map;\n\n"
                + "public final class PageDashboardApi {\n"
                + "    public Object models(WizContext wiz) {\n"
                + "        Struct root = wiz.models().get(\"struct\", Struct.class);\n"
                + "        UserStruct user = wiz.models().get(\"struct/user\", UserStruct.class);\n"
                + "        return Map.of(\"root\", root.name(), \"role\", user.role());\n"
                + "    }\n"
                + "}\n";
    }

    private String loginApi() {
        return "import com.wiz.runtime.WizContext;\n"
                + "import java.util.Map;\n\n"
                + "public final class PageDashboardApi {\n"
                + "    public Object login(WizContext wiz) {\n"
                + "        wiz.session().set(Map.of(\"id\", \"u1\", \"email\", \"u1@example.com\", \"name\", \"User One\", \"role\", \"admin\"));\n"
                + "        return Map.of(\"ok\", true);\n"
                + "    }\n"
                + "}\n";
    }
}
