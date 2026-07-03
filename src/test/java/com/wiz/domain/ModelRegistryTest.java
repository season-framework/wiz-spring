package com.wiz.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import com.wiz.build.ProjectBuildService;
import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizRequest;
import com.wiz.runtime.WizResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesProjectLocalAndPortalModelNamespaces() throws Exception {
        ProjectContext project = projectWithModels();
        ModelRegistry models = new ModelRegistry();

        try (WizContext context = new WizContext(WizRequest.builder().build(), new WizResponse(), project, models)) {
            Object rootStruct = context.models().get("struct", Object.class);
            Object userStruct = context.models().get("struct/user", Object.class);
            Object userEntity = context.models().get("db/user", Object.class);
            Object portalStruct = context.models().get("portal/post/struct", Object.class);
            Object postService = context.models().get("portal/post/struct/post", Object.class);
            Object seasonSession = context.models().get("portal/season/session", Object.class);

            assertEquals("com.wiz.app.model.Struct", rootStruct.getClass().getName());
            assertEquals("root", invoke(rootStruct, "name"));
            assertEquals("main", invoke(userStruct, "projectName"));
            assertEquals("entity", invoke(userEntity, "kind"));
            assertEquals("post-root", invoke(portalStruct, "name"));
            assertEquals("post-service", invoke(postService, "name"));
            assertEquals("session", invoke(seasonSession, "name"));
        }
    }

    @Test
    void cachesProjectModelsPerRequestContext() throws Exception {
        ProjectContext project = projectWithModels();
        ModelRegistry models = new ModelRegistry();

        try (WizContext context = new WizContext(WizRequest.builder().build(), new WizResponse(), project, models)) {
            Object first = context.models().get("struct", Object.class);
            Object second = context.models().get("struct", Object.class);

            assertSame(first, second);
        }
    }

    @Test
    void failsClearlyForUnknownNamespace() throws Exception {
        ProjectContext project = projectWithModels();

        try (WizContext context = new WizContext(WizRequest.builder().build(), new WizResponse(), project, new ModelRegistry())) {
            assertThrows(IllegalArgumentException.class, () -> context.models().get("missing/service", Object.class));
        }
    }

    private ProjectContext projectWithModels() throws Exception {
        Path workspace = tempDir.resolve("workspace-" + java.util.UUID.randomUUID());
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        removeJavaSources(project);
        Files.writeString(project.modelRoot().resolve("Struct.java"), "public final class Struct { public String name() { return \"root\"; } }\n");
        Files.createDirectories(project.modelRoot().resolve("struct"));
        Files.writeString(project.modelRoot().resolve("struct/UserStruct.java"), "import com.wiz.runtime.WizContext;\n"
                + "public final class UserStruct {\n"
                + "    private final WizContext wiz;\n"
                + "    public UserStruct(WizContext wiz) { this.wiz = wiz; }\n"
                + "    public String projectName() { return wiz.workspace().name(); }\n"
                + "}\n");
        Files.createDirectories(project.modelRoot().resolve("db"));
        Files.writeString(project.modelRoot().resolve("db/UserEntity.java"), "public final class UserEntity { public String kind() { return \"entity\"; } }\n");
        Files.createDirectories(project.sourceRoot().resolve("portal/post"));
        Files.writeString(project.sourceRoot().resolve("portal/post/portal.json"), "{\"use_model\":true}\n");
        Files.createDirectories(project.sourceRoot().resolve("portal/post/model/struct"));
        Files.writeString(project.sourceRoot().resolve("portal/post/model/PostStruct.java"), "public final class PostStruct { public String name() { return \"post-root\"; } }\n");
        Files.writeString(project.sourceRoot().resolve("portal/post/model/struct/PostService.java"), "public final class PostService { public String name() { return \"post-service\"; } }\n");
        Files.createDirectories(project.sourceRoot().resolve("portal/season"));
        Files.writeString(project.sourceRoot().resolve("portal/season/portal.json"), "{\"use_model\":true}\n");
        Files.createDirectories(project.sourceRoot().resolve("portal/season/model"));
        Files.writeString(project.sourceRoot().resolve("portal/season/model/Session.java"), "public final class Session { public String name() { return \"session\"; } }\n");
        new ProjectBuildService().build(project, true, "bundle");
        return project;
    }

    private void removeJavaSources(ProjectContext project) throws Exception {
        try (var paths = Files.walk(project.sourceRoot())) {
            for (Path source : paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java")).toList()) {
                Files.delete(source);
            }
        }
    }

    private Object invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }
}
