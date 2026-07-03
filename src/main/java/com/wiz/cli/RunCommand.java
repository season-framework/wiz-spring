package com.wiz.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.wiz.WizSpringApplication;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "run", mixinStandardHelpOptions = true, description = "Run the WIZ Spring server.")
public class RunCommand implements Callable<Integer> {

    @Option(names = "--root", description = "WIZ workspace root.")
    private Path root = Path.of(".");

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
        WizSpringApplication.RunSettings settings = WizSpringApplication.resolveRunSettings(
                root.toAbsolutePath().normalize().toString(),
                host,
                port,
                bundle,
                log == null ? null : log.toAbsolutePath().normalize().toString(),
                profile == null || profile.isBlank() ? WizSpringApplication.DEFAULT_RUN_PROFILE : profile.trim(),
                profile != null && !profile.isBlank());
        if (dryRun) {
            System.out.println("root=" + settings.workspace());
            System.out.println("host=" + settings.host());
            System.out.println("port=" + settings.port());
            if (settings.portChanged()) {
                System.out.println("requested-port=" + settings.requestedPort());
            }
            System.out.println("bundle=" + settings.bundle());
            System.out.println("profile=" + settings.profile());
            if (settings.log() != null && !settings.log().isBlank()) {
                System.out.println("log=" + settings.log());
            }
            return 0;
        }
        if (settings.portChanged()) {
            System.out.println("Port " + settings.requestedPort() + " is busy; using " + settings.port() + ".");
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
