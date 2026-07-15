package com.wiz.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.wiz.build.BuildLogger;
import com.wiz.build.BuildResult;
import com.wiz.build.ProjectBuildService;
import com.wiz.core.WorkspacePackageService;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "build", mixinStandardHelpOptions = true, description = "Build the single WIZ Spring workspace.")
public class BuildCommand implements Callable<Integer> {

    @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
    private Path root;

    @Option(names = "--clean", description = "Clean generated build and bundle directories first.")
    private boolean clean;

    @Option(names = "--package", description = "Set the Java package root before the first successful bundle build.")
    private String packageRoot;

    @Option(names = "--phase", description = "Build phase to run: reconstruct, compile, bundle.")
    private String phase = "bundle";

    @Override
    public Integer call() throws Exception {
        PathService paths = pathService(root);
        WorkspacePackageService.PackageSelection selection = new WorkspacePackageService()
                .selectForBuild(paths, packageRoot);
        ProjectContext context = selection.context();
        if (selection.changed()) {
            System.out.println("Java package updated for initial build: " + context.packageRoot());
        }
        BuildResult result = new ProjectBuildService().build(context, clean || selection.changed(), phase, BuildLogger.console());
        System.out.println(result.message());
        System.out.println("Phases: " + String.join(",", result.phases()));
        return result.exitCode();
    }

    static PathService pathService(Path root) {
        return WorkspaceRootResolver.pathService(root, "build");
    }
}
