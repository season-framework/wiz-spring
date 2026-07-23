package com.wiz.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.wiz.build.BuildLogger;
import com.wiz.build.BuildResult;
import com.wiz.build.MavenExecutableResolver;
import com.wiz.build.ProjectBuildService;
import com.wiz.runtime.PathService;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "build", mixinStandardHelpOptions = true, description = "Build the single WIZ Spring workspace.")
public class BuildCommand implements Callable<Integer> {

    @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
    private Path root;

    @Option(names = "--clean", description = "Recreate generated build state; publish the bundle only after a successful bundle build.")
    private boolean clean;

    @Option(names = "--package", description = "Change the Java package root. Package changes automatically use a clean build.")
    private String packageRoot;

    @Option(names = "--phase", description = "Build phase to run: reconstruct, compile, bundle.")
    private String phase = "bundle";

    @Override
    public Integer call() throws Exception {
        PathService paths = pathService(root);
        String requestedPhase = phase == null || phase.isBlank() ? "bundle" : phase.trim();
        if (!ProjectBuildService.isSupportedPhase(requestedPhase)) {
            System.out.println("Supported build phases: reconstruct, compile, bundle");
            return 2;
        }
        Path appRoot = paths.root().resolve("src/app");
        if (!ProjectBuildService.hasBuildSource(appRoot)) {
            System.out.println(ProjectBuildService.missingBuildSourceMessage(paths.root()));
            return 2;
        }
        Path maven = requireMavenWhenNeeded(paths.root(), requestedPhase);
        if (maven != null) {
            System.out.println("Maven: " + maven);
        }
        ProjectBuildService.PackageBuildResult packageBuild = new ProjectBuildService()
                .build(paths, packageRoot, clean, requestedPhase, BuildLogger.console());
        if (packageBuild.packageChanged()) {
            System.out.println("Java package updated: " + packageBuild.packageRoot());
        }
        BuildResult result = packageBuild.result();
        System.out.println(result.message());
        System.out.println("Phases: " + String.join(",", result.phases()));
        return result.exitCode();
    }

    static PathService pathService(Path root) {
        return WorkspaceRootResolver.pathService(root, "build");
    }

    private static Path requireMavenWhenNeeded(Path workspaceRoot, String phase) {
        if (phase.equals("reconstruct") || !Files.isRegularFile(workspaceRoot.resolve("pom.xml"))) {
            return null;
        }
        if (!phase.equals("compile") && !phase.equals("bundle")) {
            return null;
        }
        return MavenExecutableResolver.require(workspaceRoot);
    }
}
