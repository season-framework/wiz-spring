package com.wiz.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

class StructRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesStructNamespaceShortcut() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createProject("main", null, null);
        removeJavaSources(project);
        Files.writeString(project.modelRoot().resolve("Struct.java"), "public final class Struct {}\n");
        Files.createDirectories(project.modelRoot().resolve("struct"));
        Files.writeString(project.modelRoot().resolve("struct/UserStruct.java"), "public final class UserStruct { public String name() { return \"user\"; } }\n");
        new ProjectBuildService().build(project, true, "bundle");
        ModelRegistry models = new ModelRegistry();
        StructRegistry structs = new StructRegistry(models);

        try (WizContext context = new WizContext(WizRequest.builder().build(), new WizResponse(), project, models)) {
            Object value = structs.get(context, "user", Object.class);

            Method method = value.getClass().getMethod("name");
            assertEquals("user", method.invoke(value));
        }
    }

    private void removeJavaSources(ProjectContext project) throws Exception {
        try (var paths = Files.walk(project.sourceRoot())) {
            for (Path source : paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java")).toList()) {
                Files.delete(source);
            }
        }
    }
}