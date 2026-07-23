package com.wiz.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.wiz.build.BuildResult;
import com.wiz.build.ProjectBuildService;
import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.domain.ModelRegistry;
import com.wiz.http.ResponseEnvelope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;

class ProjectExtensionLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsProjectAuthAndSessionImplementationsFromBundle() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        Files.writeString(project.modelRoot().resolve("AuthService.java"), authServiceJava());
        Files.writeString(project.modelRoot().resolve("SessionService.java"), sessionServiceJava());

        BuildResult build = new ProjectBuildService().build(project, true, "bundle");
        assertTrue(build.success(), build.message());

        MockHttpSession httpSession = new MockHttpSession();
        try (ProjectRuntimeCache cache = new ProjectRuntimeCache();
                WizContext context = new WizContext(WizRequest.builder().session(httpSession).build(),
                        new WizResponse(), project, new ModelRegistry(cache), null, cache)) {
            assertEquals("com.wiz.app.application.model.AuthService", context.auth().getClass().getName());
            assertEquals("com.wiz.app.application.model.SessionService", context.session().getClass().getName());
            ResponseEnvelope envelope = (ResponseEnvelope) context.auth().check(context).entity();
            assertEquals(Map.of("projectAuth", true), envelope.data());
        }
    }

    private String authServiceJava() {
        return "import java.util.Map;\n"
                + "import com.wiz.runtime.WizContext;\n"
                + "import com.wiz.runtime.WizResult;\n\n"
                + "public class AuthService extends com.wiz.session.AuthService {\n"
                + "    public WizResult check(WizContext context) {\n"
                + "        return context.response().status(200, Map.of(\"projectAuth\", true));\n"
                + "    }\n"
                + "}\n";
    }

    private String sessionServiceJava() {
        return "import jakarta.servlet.http.HttpSession;\n\n"
                + "public class SessionService extends com.wiz.session.SessionService {\n"
                + "    public SessionService(HttpSession httpSession) {\n"
                + "        super(httpSession);\n"
                + "    }\n"
                + "}\n";
    }
}
