package com.wiz.cli;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "service", mixinStandardHelpOptions = true, description = "Manage WIZ Spring systemd services.", subcommands = {
        ServiceCommand.ListServices.class,
        ServiceCommand.Regist.class,
        ServiceCommand.Unregist.class,
        ServiceCommand.Status.class,
        ServiceCommand.Start.class,
        ServiceCommand.Stop.class,
        ServiceCommand.Restart.class
})
public class ServiceCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        return 0;
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "List WIZ services.")
    static class ListServices implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            Path systemd = Path.of("/etc/systemd/system");
            if (!Files.isDirectory(systemd)) {
                System.out.println("systemd service directory not found: " + systemd);
                return 0;
            }
            try (Stream<Path> files = Files.list(systemd)) {
                files.map(path -> path.getFileName().toString())
                        .filter(name -> name.startsWith("wiz.") && name.endsWith(".service"))
                        .sorted()
                        .forEach(System.out::println);
            }
            return 0;
        }
    }

    @Command(name = "regist", mixinStandardHelpOptions = true, description = "Register a WIZ systemd service.")
    static class Regist implements Callable<Integer> {
        @Parameters(index = "0", description = "Service name.")
        private String name;

        @Parameters(index = "1", arity = "0..1", description = "HTTP port.")
        private Integer port;

        @Parameters(index = "2", arity = "0..1", description = "Use 'bundle' to pass --bundle to run.")
        private String bundleToken;

        @Option(names = "--root", description = "WIZ workspace root.")
        private Path root = Path.of(".");

        @Option(names = "--jar", description = "wiz-spring jar path.")
        private Path jar;

        @Option(names = "--log", description = "Log file path.")
        private Path log;

        @Option(names = "--dry-run", description = "Print generated files without writing them.")
        private boolean dryRun;

        @Override
        public Integer call() throws Exception {
            ensureLinux();
            String serviceName = serviceName(name);
            Path jarPath = jar == null ? currentRuntimePath() : jar.toAbsolutePath().normalize();
            Path rootPath = root.toAbsolutePath().normalize();
            Path logPath = log == null ? Path.of("/var/log/wiz").resolve(name.toLowerCase(java.util.Locale.ROOT) + ".log") : log.toAbsolutePath().normalize();
            Path commandPath = Path.of("/usr/local/bin").resolve(serviceName);
            Path servicePath = Path.of("/etc/systemd/system").resolve(serviceName + ".service");
            String script = script(jarPath, rootPath, port == null ? 8080 : port, "bundle".equalsIgnoreCase(String.valueOf(bundleToken)), logPath);
            String unit = unit(serviceName, commandPath);
            if (dryRun) {
                System.out.println(commandPath);
                System.out.println(script);
                System.out.println(servicePath);
                System.out.println(unit);
                return 0;
            }
            Files.createDirectories(commandPath.getParent());
            Files.createDirectories(servicePath.getParent());
            Files.createDirectories(logPath.getParent());
            Files.writeString(commandPath, script);
            commandPath.toFile().setExecutable(true, false);
            Files.writeString(servicePath, unit);
            runSystemctl("daemon-reload");
            runSystemctl("enable", serviceName);
            System.out.println("Service registered: " + serviceName);
            return 0;
        }
    }

    @Command(name = "unregist", mixinStandardHelpOptions = true, description = "Unregister a WIZ systemd service.")
    static class Unregist implements Callable<Integer> {
        @Parameters(index = "0", description = "Service name.")
        private String name;

        @Option(names = "--dry-run", description = "Print files that would be removed.")
        private boolean dryRun;

        @Override
        public Integer call() throws Exception {
            ensureLinux();
            String serviceName = serviceName(name);
            Path commandPath = Path.of("/usr/local/bin").resolve(serviceName);
            Path servicePath = Path.of("/etc/systemd/system").resolve(serviceName + ".service");
            if (dryRun) {
                System.out.println(commandPath);
                System.out.println(servicePath);
                return 0;
            }
            runSystemctl("disable", serviceName);
            Files.deleteIfExists(commandPath);
            Files.deleteIfExists(servicePath);
            runSystemctl("daemon-reload");
            System.out.println("Service unregistered: " + serviceName);
            return 0;
        }
    }

    @Command(name = "status", mixinStandardHelpOptions = true, description = "Show WIZ service status.")
    static class Status implements Callable<Integer> {
        @Parameters(index = "0", description = "Service name.")
        private String name;

        public Integer call() throws Exception {
            ensureLinux();
            return runSystemctl("status", serviceName(name));
        }
    }

    @Command(name = "start", mixinStandardHelpOptions = true, description = "Start WIZ service(s).")
    static class Start extends ServiceAction {
        Start() {
            super("start");
        }
    }

    @Command(name = "stop", mixinStandardHelpOptions = true, description = "Stop WIZ service(s).")
    static class Stop extends ServiceAction {
        Stop() {
            super("stop");
        }
    }

    @Command(name = "restart", mixinStandardHelpOptions = true, description = "Restart WIZ service(s).")
    static class Restart extends ServiceAction {
        Restart() {
            super("restart");
        }
    }

    static class ServiceAction implements Callable<Integer> {
        private final String action;

        @Parameters(index = "0", arity = "0..1", description = "Service name. If omitted, all wiz.* services are targeted.")
        private String name;

        ServiceAction(String action) {
            this.action = action;
        }

        @Override
        public Integer call() throws Exception {
            ensureLinux();
            if (name != null && !name.isBlank()) {
                return runSystemctl(action, serviceName(name));
            }
            int exit = 0;
            for (String service : serviceNames()) {
                exit = Math.max(exit, runSystemctl(action, service));
            }
            return exit;
        }
    }

    private static void ensureLinux() {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")) {
            throw new IllegalStateException("Service management is only supported on Linux");
        }
    }

    private static List<String> serviceNames() throws IOException {
        Path systemd = Path.of("/etc/systemd/system");
        if (!Files.isDirectory(systemd)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(systemd)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("wiz.") && name.endsWith(".service"))
                    .map(name -> name.substring(0, name.length() - ".service".length()))
                    .sorted()
                    .toList();
        }
    }

    private static String serviceName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Service name is required");
        }
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        if (!normalized.startsWith("wiz.")) {
            normalized = "wiz." + normalized;
        }
        if (!normalized.matches("wiz\\.[a-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid service name: " + name);
        }
        return normalized;
    }

    private static Path currentRuntimePath() {
        try {
            return Path.of(ServiceCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Failed to resolve current runtime path", exception);
        }
    }

    private static String script(Path jar, Path root, int port, boolean bundle, Path log) {
        return "#!/bin/sh\n"
                + "exec java -jar " + shell(jar.toString())
                + " run --root " + shell(root.toString())
                + " --host 0.0.0.0 --port " + port
                + (bundle ? " --bundle" : "")
                + " --log " + shell(log.toString())
                + "\n";
    }

    private static String unit(String serviceName, Path commandPath) {
        return "[Unit]\n"
                + "Description=" + serviceName + "\n"
                + "After=network.target\n\n"
                + "[Service]\n"
                + "Type=simple\n"
                + "ExecStart=" + commandPath + "\n"
                + "Restart=on-failure\n\n"
                + "[Install]\n"
                + "WantedBy=multi-user.target\n";
    }

    private static int runSystemctl(String... args) throws IOException, InterruptedException {
        java.util.ArrayList<String> argv = new java.util.ArrayList<>();
        argv.add("systemctl");
        argv.addAll(List.of(args));
        Process process = new ProcessBuilder(argv).inheritIO().start();
        return process.waitFor();
    }

    private static String shell(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
