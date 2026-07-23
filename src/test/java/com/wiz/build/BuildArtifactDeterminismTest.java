package com.wiz.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.jar.JarFile;

import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildArtifactDeterminismTest {

    @TempDir
    Path tempDir;

    @Test
    void appApiJarUsesSortedEntriesAndStableTimestamps() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path app = workspace.resolve("src/app/page.simple");
        Path secondApp = workspace.resolve("src/app/page.second");
        Files.createDirectories(app);
        Files.createDirectories(secondApp);
        Files.createDirectories(workspace.resolve("config"));
        Files.writeString(app.resolve("api.java"), "public final class PageSimpleApi {}\n");
        Files.writeString(secondApp.resolve("api.java"), "public final class PageSecondApi {}\n");
        ProjectContext project = new PathService(workspace).workspaceContext();

        BuildResult first = new ProjectBuildService().build(project, true, "compile");
        assertTrue(first.success(), first.message());
        byte[] firstJar = Files.readAllBytes(ProjectBuildLayout.appApiJar(project));

        ArrayList<String> entries = new ArrayList<>();
        try (JarFile jar = new JarFile(ProjectBuildLayout.appApiJar(project).toFile())) {
            var enumeration = jar.entries();
            while (enumeration.hasMoreElements()) {
                var entry = enumeration.nextElement();
                entries.add(entry.getName());
                assertEquals(0L, entry.getTime());
            }
        }
        assertTrue(entries.size() >= 2, "Expected at least two compiled class entries: " + entries);
        assertTrue(entries.contains("com/wiz/app/web/api/PageSimpleApi.class"));
        assertTrue(entries.contains("com/wiz/app/web/api/PageSecondApi.class"));
        assertEquals(entries.stream().sorted().toList(), entries);

        BuildResult second = new ProjectBuildService().build(project, false, "compile");
        assertTrue(second.success(), second.message());
        assertArrayEquals(firstJar, Files.readAllBytes(ProjectBuildLayout.appApiJar(project)));
    }
}
