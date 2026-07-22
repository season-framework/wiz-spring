package com.wiz.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.wiz.WizSpringApplication;
import com.wiz.logging.ProcessLogManager;
import com.wiz.runtime.BuildMarkerService;
import com.wiz.runtime.PathService;
import com.wiz.runtime.WizSpringVersion;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "run", mixinStandardHelpOptions = true, description = "Run the WIZ Spring server.")
public class RunCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
    private Path root;

    @Option(names = "--host", description = "HTTP bind host.")
    private String host;

    @Option(names = "--port", description = "HTTP bind port.")
    private Integer port;

    @Option(names = "--profile", description = "Spring profile to activate. Defaults to dev for wiz-spring run.")
    private String profile;

    @Option(names = "--dry-run", description = "Print resolved run settings without starting the server.")
    private boolean dryRun;

    @Option(names = "--bundle", description = "Run in bundle compatibility mode.")
    private boolean bundle;

    @Option(names = "--log", description = "Tee server stdout/stderr to a bounded rolling file (10 MiB x 15 files).")
    private Path log;

    @Override
    public Integer call() {
        Path workspaceRoot = WorkspaceRootResolver.resolve(root, "run");
        WizSpringApplication.RunSettings settings = WizSpringApplication.resolveRunSettings(
                workspaceRoot.toString(),
                host,
                port,
                bundle,
                log == null ? null : log.toAbsolutePath().normalize().toString(),
                profile == null || profile.isBlank() ? WizSpringApplication.DEFAULT_RUN_PROFILE : profile.trim(),
                profile != null && !profile.isBlank());
        var out = spec.commandLine().getOut();
        if (dryRun) {
            printSettings(out, settings);
            return 0;
        }
        requireCompletedBundle(workspaceRoot);
        ProcessLogManager.install(settings.log());
        var runtimeOut = settings.log() == null
                ? out
                : new java.io.PrintWriter(System.out, true);
        runtimeOut.println("Starting WIZ Spring server (pid=" + ProcessHandle.current().pid() + ")");
        printSettings(runtimeOut, settings);
        if (settings.portChanged()) {
            runtimeOut.println("Port " + settings.requestedPort() + " is busy; using " + settings.port() + ".");
        }
        runtimeOut.flush();
        WizSpringApplication.runServer(settings);
        return 0;
    }

    private static void requireCompletedBundle(Path workspaceRoot) {
        var project = new PathService(workspaceRoot).workspaceContext();
        var marker = new BuildMarkerService().read(project)
                .orElseThrow(() -> incompleteBundle(workspaceRoot, "bundle/.wiz-build.json is missing or invalid"));
        Object phases = marker.get("buildPhases");
        boolean bundled = phases instanceof Iterable<?> values
                && java.util.stream.StreamSupport.stream(values.spliterator(), false)
                        .anyMatch(value -> "bundle".equals(String.valueOf(value)));
        if (!bundled || !java.nio.file.Files.isDirectory(project.bundleRoot().resolve("src/app"))) {
            throw incompleteBundle(workspaceRoot, "the last completed build does not contain a runnable app bundle");
        }
        String buildVersion = String.valueOf(marker.getOrDefault("runtimeVersion", ""));
        String runtimeVersion = WizSpringVersion.current();
        if (!runtimeVersion.equals(buildVersion)) {
            throw incompleteBundle(workspaceRoot,
                    "bundle runtime version " + (buildVersion.isBlank() ? "is unknown" : "is " + buildVersion)
                            + " but this runtime is " + runtimeVersion);
        }
    }

    private static IllegalStateException incompleteBundle(Path workspaceRoot, String reason) {
        return new IllegalStateException("WIZ Spring cannot run " + workspaceRoot + ": " + reason + ". "
                + "Run 'wiz-spring build --root " + workspaceRoot + " --clean' first; "
                + "the build requires an executable workspace mvnw or Maven on PATH.");
    }

    private static void printSettings(java.io.PrintWriter out, WizSpringApplication.RunSettings settings) {
        out.println("root=" + settings.workspace());
        out.println("host=" + settings.host());
        out.println("port=" + settings.port());
        if (settings.portChanged()) {
            out.println("requested-port=" + settings.requestedPort());
        }
        out.println("bundle=" + settings.bundle());
        out.println("profile=" + settings.profile());
        out.println("config=" + settings.additionalConfigLocations());
        out.println("java=" + System.getProperty("java.version") + " (" + System.getProperty("java.home") + ")");
        out.println("log=" + (settings.log() == null || settings.log().isBlank() ? "console" : settings.log()));
    }
}
