package com.wiz.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.wiz.build.ProjectBuildService;
import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.dispatch.AppApiDispatcher;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.ProjectRegistry;
import com.wiz.runtime.SafePath;
import com.wiz.runtime.WizRequest;
import com.wiz.runtime.WizRuntime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecurityRegressionTest {

    @TempDir
    Path tempDir;

    @Test
    void safePathRejectsExistingSymlinkEscape() throws Exception {
        Path base = tempDir.resolve("base");
        Path outside = tempDir.resolve("outside.txt");
        Files.createDirectories(base);
        Files.writeString(outside, "secret");
        createSymbolicLinkOrSkip(base.resolve("leak.txt"), outside);

        SafePath safePath = new SafePath(base);

        assertThrows(IllegalArgumentException.class, () -> safePath.resolveExisting("leak.txt"));
    }

    @Test
    void projectCopyRejectsSymlinks() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path source = tempDir.resolve("source");
        Path outside = tempDir.resolve("outside.txt");
        new WorkspaceService().createWorkspace(workspace);
        Files.createDirectories(source.resolve("src/app/page.local"));
        Files.createDirectories(source.resolve("src/assets"));
        Files.writeString(source.resolve("src/app/page.local/app.json"), "{}\n");
        Files.writeString(outside, "secret");
        createSymbolicLinkOrSkip(source.resolve("src/assets/leak.txt"), outside);

        ProjectService service = new ProjectService(new PathService(workspace));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.createApp(null, source));
        assertTrue(exception.getMessage().contains("Symbolic links are not allowed"));
    }

    @Test
    void buildCopyRejectsSymlinks() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path outside = tempDir.resolve("outside.txt");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        Files.writeString(outside, "secret");
        createSymbolicLinkOrSkip(project.assetsRoot().resolve("leak.txt"), outside);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ProjectBuildService().build(project, true, "bundle"));
        assertTrue(exception.getMessage().contains("Symbolic links are not allowed"));
    }

    @Test
    void gitUriPolicyAllowsOnlyStructuredGitUris() {
        assertEquals("https://github.com/example/project.git", GitUriPolicy.validate("https://github.com/example/project.git"));
        assertEquals("ssh://git@example.com/example/project.git", GitUriPolicy.validate("ssh://git@example.com/example/project.git"));
        assertEquals("git@example.com:example/project.git", GitUriPolicy.validate("git@example.com:example/project.git"));

        assertThrows(IllegalArgumentException.class, () -> GitUriPolicy.validate("file:///tmp/project.git"));
        assertThrows(IllegalArgumentException.class, () -> GitUriPolicy.validate("/tmp/project.git"));
        assertThrows(IllegalArgumentException.class, () -> GitUriPolicy.validate("--upload-pack=/tmp/hook"));
        assertThrows(IllegalArgumentException.class, () -> GitUriPolicy.validate("https://github.com/example/project.git?token=secret"));
    }

    @Test
    void secretMaskerRedactsCredentialsAndCommonSecretKeys() {
        String masked = SecretMasker.mask("fatal https://user:token123@example.com/repo.git token=abc password=hunter2");

        assertTrue(masked.contains("https://***@example.com/repo.git"));
        assertTrue(masked.contains("token=***"));
        assertTrue(masked.contains("password=***"));
        assertTrue(!masked.contains("token123"));
        assertTrue(!masked.contains("hunter2"));
    }

    @Test
    void appApiDispatchRestoresContextClassLoader() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        ProjectContext project = new ProjectService(new PathService(workspace)).createApp(null, null);
        new ProjectBuildService().build(project, true, "bundle");
        AppApiDispatcher dispatcher = new AppApiDispatcher(new WizRuntime(new ProjectRegistry(new PathService(workspace))));
        ClassLoader original = Thread.currentThread().getContextClassLoader();

        dispatcher.dispatch(WizRequest.builder().method("POST").build(), "page.dashboard", "overview", "");

        assertSame(original, Thread.currentThread().getContextClassLoader());
    }

    private void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "Symbolic links are not available: " + exception.getMessage());
        }
    }
}