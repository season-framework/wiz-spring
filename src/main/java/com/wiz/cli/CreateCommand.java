package com.wiz.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.wiz.build.BuildLogger;
import com.wiz.build.BuildResult;
import com.wiz.build.ProjectBuildService;
import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "create", mixinStandardHelpOptions = true, description = "Create a new single WIZ Spring workspace.")
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

    @Override
    public Integer call() throws Exception {
        WorkspaceService service = new WorkspaceService();
        WorkspaceService.CreatedWorkspace workspace = service.createWorkspace(path, packageRoot);
        ProjectContext context = new ProjectService(new PathService(workspace.root()))
                .createApp(packageRoot, uri, sourcePath);
        System.out.println("Workspace created: " + workspace.root());
        System.out.println("Java package: " + context.packageRoot());
        System.out.println("Port: " + workspace.port());
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
}
