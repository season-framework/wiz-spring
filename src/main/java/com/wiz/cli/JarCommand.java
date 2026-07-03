package com.wiz.cli;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.wiz.build.BuildLogger;
import com.wiz.build.BuildResult;
import com.wiz.build.ProjectBuildService;
import com.wiz.build.StandaloneProjectJarService;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "jar", mixinStandardHelpOptions = true, description = "Package the WIZ Spring workspace as one executable jar.")
public class JarCommand implements Callable<Integer> {

    @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
    private Path root;

    @Option(names = "--output", description = "Output jar path. Defaults to target/wiz-app.jar.")
    private Path output;

    @Option(names = "--runtime-jar", description = "wiz-spring executable jar path. Defaults to the currently running jar.")
    private Path runtimeJar;

    @Option(names = "--clean", description = "Clean generated build and bundle directories before packaging.")
    private boolean clean;

    @Option(names = "--skip-build", description = "Package the existing bundle without running build.")
    private boolean skipBuild;

    @Override
    public Integer call() throws Exception {
        PathService paths = BuildCommand.pathService(root);
        ProjectContext context = paths.workspaceContext();
        if (!skipBuild) {
            BuildResult result = new ProjectBuildService().build(context, clean, "bundle", BuildLogger.console());
            if (!result.success()) {
                System.out.println(result.message());
                System.out.println("Phases: " + String.join(",", result.phases()));
                return result.exitCode();
            }
        }
        StandaloneProjectJarService jarService = new StandaloneProjectJarService();
        Path jar = jarService.packageJar(paths.root(), context, runtimeJar == null ? currentRuntimePath() : runtimeJar, output);
        System.out.println("Executable jar created: " + jar);
        System.out.println("Checksum: " + jarService.checksumPath(jar));
        System.out.println("Run: java -jar " + jar);
        return 0;
    }

    private Path currentRuntimePath() {
        String classPath = System.getProperty("java.class.path", "");
        if (!classPath.isBlank() && !classPath.contains(File.pathSeparator)) {
            Path candidate = Path.of(classPath).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        try {
            return Path.of(JarCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Failed to resolve current runtime jar path", exception);
        }
    }
}
