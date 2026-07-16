package com.wiz.cli;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.wiz.build.BuildLogger;
import com.wiz.build.BuildResult;
import com.wiz.build.ProjectBuildService;
import com.wiz.core.CodexWorkspaceService;
import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "create",
        mixinStandardHelpOptions = true,
        description = "Create a WIZ Spring workspace with automatic .codex and bundled .github setup.")
public class CreateCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "PATH", description = "Workspace path or name.")
    private Path path;

    @Option(names = "--package", required = true, description = "Base Java package for generated source, for example com.example.demo.")
    private String packageRoot;

    @Option(names = "--uri", description = "Git repository URI to import.")
    private String uri;

    @Option(names = "--path", description = "Local source directory to import.")
    private Path sourcePath;

    @Option(names = "--skip-build", description = "Create sources without running the initial clean build.")
    private boolean skipBuild;

    @Option(names = "--runtime-jar", description = "wiz-spring executable jar for generated MCP settings. Defaults to WIZ_RUNTIME_JAR or the currently running jar.")
    private Path runtimeJar;

    @Override
    public Integer call() throws Exception {
        Path jar = runtimeJar();
        WorkspaceService service = new WorkspaceService();
        WorkspaceService.CreatedWorkspace workspace = service.createWorkspace(path, packageRoot);
        ProjectContext context = new ProjectService(new PathService(workspace.root()))
                .createApp(packageRoot, uri, sourcePath);
        CodexWorkspaceService.SetupResult codex = new CodexWorkspaceService().setup(workspace.root(), jar);
        System.out.println("Workspace created: " + workspace.root());
        System.out.println("Java package: " + context.packageRoot());
        System.out.println("Port: " + workspace.port());
        System.out.println("Codex: .codex MCP settings and bundled .github instructions ready ("
                + codex.changedFiles() + " files written, " + codex.managedFiles() + " managed).");
        System.out.println("Config: application.yml is common, application-dev.yml is the run default, and application-prod.yml is the standalone jar default.");
        System.out.println("Session: cookie-only, HttpOnly, SameSite=Lax; dev allows HTTP and prod requires HTTPS.");
        System.out.println("Metadata: config/wiz.yml records the Java workspace format and wiz-spring version.");
        System.out.println("Git: application.yml and application-<profile>.yml are ignored; commit config/application*.example.yml instead.");
        if (!skipBuild) {
            System.out.println("Running initial clean build...");
            BuildResult result = new ProjectBuildService().build(context, true, "bundle", BuildLogger.console());
            System.out.println(result.message());
            System.out.println("Phases: " + String.join(",", result.phases()));
            if (!result.success()) {
                return result.exitCode();
            }
        }
        System.out.println("Run: wiz-spring run --root " + workspace.root() + " --port " + workspace.port());
        return 0;
    }

    private Path runtimeJar() {
        if (runtimeJar != null) {
            return requireRuntimeJar(runtimeJar, "--runtime-jar");
        }

        String configured = System.getenv("WIZ_RUNTIME_JAR");
        if (configured != null && !configured.isBlank()) {
            return requireRuntimeJar(Path.of(configured), "WIZ_RUNTIME_JAR");
        }

        String classPath = System.getProperty("java.class.path", "");
        if (!classPath.isBlank() && !classPath.contains(File.pathSeparator)) {
            Path candidate = Path.of(classPath).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        try {
            Path candidate = Path.of(CreateCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Failed to resolve current wiz-spring runtime jar", exception);
        }
        throw new IllegalArgumentException(
                "wiz-spring runtime jar could not be resolved; run the packaged jar or use --runtime-jar");
    }

    private Path requireRuntimeJar(Path path, String source) {
        Path jar = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("wiz-spring runtime jar from " + source + " not found: " + jar);
        }
        return jar;
    }
}
