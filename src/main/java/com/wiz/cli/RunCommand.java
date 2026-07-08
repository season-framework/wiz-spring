package com.wiz.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.wiz.WizSpringApplication;

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

    @Option(names = "--log", description = "Write server stdout/stderr logs to the given file.")
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
            out.println("root=" + settings.workspace());
            out.println("host=" + settings.host());
            out.println("port=" + settings.port());
            if (settings.portChanged()) {
                out.println("requested-port=" + settings.requestedPort());
            }
            out.println("bundle=" + settings.bundle());
            out.println("profile=" + settings.profile());
            if (settings.log() != null && !settings.log().isBlank()) {
                out.println("log=" + settings.log());
            }
            return 0;
        }
        if (settings.portChanged()) {
            out.println("Port " + settings.requestedPort() + " is busy; using " + settings.port() + ".");
        }
        WizSpringApplication.runServer(
                settings.workspace().toString(),
                settings.host(),
                settings.port(),
                settings.bundle(),
                settings.log(),
                settings.profile(),
                settings.profileOverride());
        return 0;
    }
}
