package com.wiz.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PugBuildServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void deletesTemporaryCompilerScriptAfterSuccess() throws Exception {
        ProjectContext project = projectWithPug();
        CapturingCommandExecutor executor = new CapturingCommandExecutor(false);

        CommandResult result = new PugBuildService(executor)
                .compile(project, ProjectBuildLayout.stagedAngularRoot(project));

        assertTrue(result.success());
        assertTrue(executor.scriptObservedDuringExecution);
        assertFalse(Files.exists(executor.script));
        assertNoWizDirectories(project.root());
    }

    @Test
    void deletesTemporaryCompilerScriptAfterFailure() throws Exception {
        ProjectContext project = projectWithPug();
        CapturingCommandExecutor executor = new CapturingCommandExecutor(true);

        assertThrows(IOException.class, () -> new PugBuildService(executor)
                .compile(project, ProjectBuildLayout.stagedAngularRoot(project)));

        assertTrue(executor.scriptObservedDuringExecution);
        assertFalse(Files.exists(executor.script));
        assertNoWizDirectories(project.root());
    }

    private ProjectContext projectWithPug() throws IOException {
        Path workspace = tempDir.resolve("workspace-" + java.util.UUID.randomUUID());
        Files.createDirectories(workspace);
        ProjectContext project = new PathService(workspace).workspaceContext();
        Path angularRoot = ProjectBuildLayout.stagedAngularRoot(project);
        Files.createDirectories(angularRoot.resolve("src"));
        Files.writeString(angularRoot.resolve("src/index.pug"), "doctype html\nhtml\n");
        return project;
    }

    private void assertNoWizDirectories(Path workspace) throws IOException {
        try (var paths = Files.walk(workspace)) {
            assertTrue(paths
                    .filter(Files::isDirectory)
                    .noneMatch(path -> path.getFileName() != null && path.getFileName().toString().equals(".wiz")));
        }
    }

    private static final class CapturingCommandExecutor extends CommandExecutor {
        private final boolean fail;
        private Path script;
        private boolean scriptObservedDuringExecution;

        private CapturingCommandExecutor(boolean fail) {
            this.fail = fail;
        }

        @Override
        public CommandResult run(String phase, Path workspaceRoot, Path cwd, List<String> argv,
                Duration timeout, int outputCapBytes, BuildLogger logger) throws IOException {
            script = Path.of(argv.get(1));
            scriptObservedDuringExecution = Files.isRegularFile(script);
            if (fail) {
                throw new IOException("simulated node failure");
            }
            return new CommandResult(phase, argv, cwd, 0, 1, false, false, "ok");
        }
    }
}
