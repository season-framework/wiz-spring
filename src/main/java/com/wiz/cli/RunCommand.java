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
    private String host = "127.0.0.1";

    @Option(names = "--port", description = "HTTP bind port.")
    private int port = 8080;

    @Option(names = "--dry-run", description = "Print resolved run settings without starting the server.")
    private boolean dryRun;

    @Option(names = "--bundle", description = "Run in bundle compatibility mode.")
    private boolean bundle;

    @Option(names = "--log", description = "Write Spring logs to the given file.")
    private Path log;

    @Override
    public Integer call() {
        Path resolvedRoot = root.toAbsolutePath().normalize();
        if (dryRun) {
            System.out.println("root=" + resolvedRoot);
            System.out.println("host=" + host);
            System.out.println("port=" + port);
            System.out.println("bundle=" + bundle);
            if (log != null) {
                System.out.println("log=" + log.toAbsolutePath().normalize());
            }
            return 0;
        }
        WizSpringApplication.runServer(resolvedRoot.toString(), host, port, bundle, log == null ? null : log.toAbsolutePath().normalize().toString());
        return 0;
    }
}
