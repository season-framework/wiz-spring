package com.wiz.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.wiz.build.BuildLogger;
import com.wiz.build.BuildResult;
import com.wiz.build.ProjectBuildService;
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

    @Option(names = "--phase", description = "Build phase to run: reconstruct, compile, bundle.")
    private String phase = "bundle";

    @Override
    public Integer call() throws Exception {
        ProjectContext context = pathService(root).workspaceContext();
        BuildResult result = new ProjectBuildService().build(context, clean, phase, BuildLogger.console());
        System.out.println(result.message());
        System.out.println("Phases: " + String.join(",", result.phases()));
        return result.exitCode();
    }

    static PathService pathService(Path root) {
        PathService detector = new PathService(Path.of("."));
        Path workspaceRoot = root == null
                ? detector.findWorkspaceRoot(Path.of("."))
                        .orElseThrow(() -> new IllegalArgumentException("WIZ workspace root not found"))
                : root.toAbsolutePath().normalize();
        return new PathService(workspaceRoot);
    }
}
