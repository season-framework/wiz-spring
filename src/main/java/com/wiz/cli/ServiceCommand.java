package com.wiz.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import com.wiz.WizSpringApplication;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

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

    private static final Path DEFAULT_SYSTEMD_DIR = Path.of("/etc/systemd/system");
    private static final Path DEFAULT_BIN_DIR = Path.of("/usr/local/bin");
    private static final Path DEFAULT_LOG_DIR = Path.of("/var/log/wiz");

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }

    @Command(name = "list", aliases = {"ls"}, mixinStandardHelpOptions = true, description = "List WIZ services.")
    static class ListServices implements Callable<Integer> {
        @Spec
        private CommandSpec spec;

        @Option(names = "--systemd-dir", hidden = true)
        private Path systemdDir = DEFAULT_SYSTEMD_DIR;

        @Option(names = "--bin-dir", hidden = true)
        private Path binDir = DEFAULT_BIN_DIR;

        @Option(names = "--log-dir", hidden = true)
        private Path logDir = DEFAULT_LOG_DIR;

        @Override
        public Integer call() throws Exception {
            ensureLinux();
            printServices(describeServices(systemdDir, binDir, logDir), spec.commandLine().getOut());
            return 0;
        }
    }

    @Command(name = "regist", aliases = {"install", "register"}, mixinStandardHelpOptions = true, description = "Register a WIZ systemd service.")
    static class Regist implements Callable<Integer> {
        @Spec
        private CommandSpec spec;

        @Parameters(index = "0", description = "Service name.")
        private String name;

        @Parameters(index = "1..*", arity = "0..2", description = "Optional HTTP port and/or 'bundle'.")
        private List<String> runArgs = List.of();

        @Option(names = "--root", description = "WIZ workspace root.")
        private Path root = Path.of(".");

        @Option(names = "--command", description = "Command used to launch wiz-spring in the generated service script.")
        private String command = "wiz-spring";

        @Option(names = "--jar", description = "Deprecated compatibility option; generated services use --command.")
        private Path jar;

        @Option(names = "--log", description = "Log file path.")
        private Path log;

        @Option(names = "--dry-run", description = "Print generated files without writing them.")
        private boolean dryRun;

        @Option(names = "--systemd-dir", hidden = true)
        private Path systemdDir = DEFAULT_SYSTEMD_DIR;

        @Option(names = "--bin-dir", hidden = true)
        private Path binDir = DEFAULT_BIN_DIR;

        @Option(names = "--log-dir", hidden = true)
        private Path logDir = DEFAULT_LOG_DIR;

        @Override
        public Integer call() throws Exception {
            ensureLinux();
            String serviceName = serviceName(name);
            String shortName = shortServiceName(serviceName);
            String commandName = shellCommand(command);
            Path rootPath = root.toAbsolutePath().normalize();
            Path logPath = log == null ? logDir.resolve(shortName) : log.toAbsolutePath().normalize();
            Path commandPath = binDir.resolve(serviceName);
            Path servicePath = systemdDir.resolve(serviceName + ".service");
            ServiceRunArgs parsed = parseRunArgs(runArgs);
            String script = script(serviceName, commandName, rootPath, parsed.port(), parsed.bundle(), logPath);
            String unit = unit(serviceName, commandPath);
            if (dryRun) {
                var out = spec.commandLine().getOut();
                out.println(commandPath);
                out.println(script);
                out.println(servicePath);
                out.println(unit);
                return 0;
            }
            Files.createDirectories(commandPath.getParent());
            Files.createDirectories(servicePath.getParent());
            Files.createDirectories(logPath.getParent());
            Files.writeString(commandPath, script, StandardCharsets.UTF_8);
            commandPath.toFile().setExecutable(true, false);
            Files.writeString(servicePath, unit, StandardCharsets.UTF_8);
            runSystemctl("daemon-reload");
            runSystemctl("enable", serviceName);
            spec.commandLine().getOut().println("Service registered: " + serviceName);
            return 0;
        }
    }

    @Command(name = "unregist", aliases = {"uninstall", "remove", "delete", "rm", "unregister"}, mixinStandardHelpOptions = true, description = "Unregister a WIZ systemd service.")
    static class Unregist implements Callable<Integer> {
        @Spec
        private CommandSpec spec;

        @Parameters(index = "0", description = "Service name.")
        private String name;

        @Option(names = "--dry-run", description = "Print files that would be removed.")
        private boolean dryRun;

        @Option(names = "--systemd-dir", hidden = true)
        private Path systemdDir = DEFAULT_SYSTEMD_DIR;

        @Option(names = "--bin-dir", hidden = true)
        private Path binDir = DEFAULT_BIN_DIR;

        @Override
        public Integer call() throws Exception {
            ensureLinux();
            String serviceName = serviceName(name);
            Path commandPath = binDir.resolve(serviceName);
            Path servicePath = systemdDir.resolve(serviceName + ".service");
            if (dryRun) {
                var out = spec.commandLine().getOut();
                out.println(commandPath);
                out.println(servicePath);
                return 0;
            }
            runSystemctl("disable", serviceName);
            Files.deleteIfExists(commandPath);
            Files.deleteIfExists(servicePath);
            runSystemctl("daemon-reload");
            spec.commandLine().getOut().println("Service unregistered: " + serviceName);
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

        @Option(names = "--systemd-dir", hidden = true)
        private Path systemdDir = DEFAULT_SYSTEMD_DIR;

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
            for (String service : serviceNames(systemdDir)) {
                exit = Math.max(exit, runSystemctl(action, service));
            }
            return exit;
        }
    }

    private static void ensureLinux() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            throw new IllegalStateException("Service management is only supported on Linux");
        }
    }

    private static List<String> serviceNames(Path systemdDir) throws IOException {
        if (!Files.isDirectory(systemdDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(systemdDir)) {
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
        String normalized = name.toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("wiz.")) {
            normalized = "wiz." + normalized;
        }
        if (!normalized.matches("wiz\\.[a-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid service name: " + name);
        }
        return normalized;
    }

    private static String shortServiceName(String serviceName) {
        return serviceName.startsWith("wiz.") ? serviceName.substring("wiz.".length()) : serviceName;
    }

    private static ServiceRunArgs parseRunArgs(List<String> args) {
        Integer port = null;
        boolean bundle = false;
        for (String arg : args) {
            if ("bundle".equalsIgnoreCase(arg)) {
                bundle = true;
                continue;
            }
            try {
                if (port != null) {
                    throw new IllegalArgumentException("HTTP port is specified more than once");
                }
                port = Integer.parseInt(arg);
                com.wiz.core.PortFinder.validatePort(port);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Unsupported service regist argument: " + arg, exception);
            }
        }
        return new ServiceRunArgs(port, bundle);
    }

    private static String shellCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("Service command is required");
        }
        String normalized = command.trim();
        if (!normalized.matches("[A-Za-z0-9_./+~-]+")) {
            throw new IllegalArgumentException("Service command contains unsupported shell characters: " + command);
        }
        return normalized;
    }

    private static String script(String serviceName, String command, Path root, Integer port, boolean bundle, Path log) {
        return "#!/bin/bash\n"
                + "# wiz.service.name=" + shortServiceName(serviceName) + "\n"
                + "# wiz.service.root=" + root + "\n"
                + "# wiz.service.port=" + (port == null ? "config" : port) + "\n"
                + "# wiz.service.bundle=" + bundle + "\n"
                + "# wiz.service.log=" + log + "\n"
                + "# wiz.service.command=" + command + "\n"
                + "export PS1=${PS1:-wiz-service}\n"
                + "shopt -s expand_aliases\n"
                + "source /root/.bashrc\n"
                + "cd " + shell(root.toString()) + "\n"
                + "type " + shell(command) + " >/dev/null 2>&1 || { echo " + shell(command + " command not found after sourcing /root/.bashrc") + " >&2; exit 127; }\n"
                + command + " run"
                + (port == null ? "" : " --port " + port)
                + (bundle ? " --bundle" : "")
                + " --log " + shell(log.toString())
                + "\n";
    }

    private static String unit(String serviceName, Path commandPath) {
        return "[Unit]\n"
                + "Description=" + serviceName + "\n"
                + "After=syslog.target network.target\n\n"
                + "[Service]\n"
                + "Type=simple\n"
                + "Environment=\"PATH=/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin\"\n"
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

    static List<ServiceDescriptor> describeServices(Path systemdDir, Path binDir, Path logDir) throws IOException {
        if (!Files.isDirectory(systemdDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(systemdDir)) {
            return files
                    .filter(path -> {
                        String filename = path.getFileName().toString();
                        return filename.startsWith("wiz.") && filename.endsWith(".service");
                    })
                    .sorted()
                    .map(path -> serviceDescriptor(path, binDir, logDir))
                    .toList();
        }
    }

    private static ServiceDescriptor serviceDescriptor(Path servicePath, Path binDir, Path logDir) {
        String filename = servicePath.getFileName().toString();
        String serviceName = filename.substring(0, filename.length() - ".service".length());
        String name = shortServiceName(serviceName);
        Path binary = binDir.resolve(serviceName);
        Map<String, String> metadata = metadata(binary);
        String root = metadata.getOrDefault("root", "-");
        String port = displayPort(metadata.getOrDefault("port", "config"), root);
        String log = metadata.getOrDefault("log", logDir.resolve(name).toString());
        return new ServiceDescriptor(name, servicePath, binary, root, port, log);
    }

    private static String displayPort(String port, String root) {
        if (port == null || port.isBlank()) {
            return "config";
        }
        if (!"config".equalsIgnoreCase(port.trim())) {
            return port.trim();
        }
        if (root == null || root.isBlank() || "-".equals(root)) {
            return port.trim();
        }
        try {
            WizSpringApplication.RunSettings settings = WizSpringApplication.resolveRunSettings(
                    root,
                    null,
                    null,
                    false,
                    null,
                    WizSpringApplication.DEFAULT_RUN_PROFILE,
                    false);
            return String.valueOf(settings.requestedPort());
        } catch (RuntimeException exception) {
            return port.trim();
        }
    }

    private static Map<String, String> metadata(Path binary) {
        if (!Files.isRegularFile(binary)) {
            return Map.of();
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(binary, StandardCharsets.UTF_8)) {
                if (!line.startsWith("# wiz.service.")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= "# wiz.service.".length()) {
                    continue;
                }
                String key = line.substring("# wiz.service.".length(), separator).trim();
                String value = line.substring(separator + 1).trim();
                if (!key.isBlank() && !value.isBlank()) {
                    values.put(key, value);
                }
            }
        } catch (IOException exception) {
            return Map.of();
        }
        return values;
    }

    private static void printServices(List<ServiceDescriptor> services, PrintWriter out) {
        if (services.isEmpty()) {
            out.println("(no WIZ services found)");
            return;
        }
        ArrayList<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"name", "systemd", "binary", "root", "port", "log"});
        for (ServiceDescriptor service : services) {
            rows.add(new String[] {
                    service.name(),
                    service.systemd().toString(),
                    service.binary().toString(),
                    service.root(),
                    service.port(),
                    service.log()
            });
        }
        int[] widths = new int[rows.get(0).length];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i].length());
            }
        }
        String border = tableBorder(widths);
        out.println(border);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            out.println(tableRow(rows.get(rowIndex), widths));
            if (rowIndex == 0) {
                out.println(border);
            }
        }
        out.println(border);
    }

    private static String tableBorder(int[] widths) {
        StringBuilder line = new StringBuilder("+");
        for (int width : widths) {
            line.append("-".repeat(width + 2)).append("+");
        }
        return line.toString();
    }

    private static String tableRow(String[] row, int[] widths) {
        StringBuilder line = new StringBuilder("|");
        for (int i = 0; i < row.length; i++) {
            line.append(' ').append(pad(row[i], widths[i])).append(" |");
        }
        return line.toString();
    }

    private static String pad(String value, int width) {
        return value + " ".repeat(Math.max(0, width - value.length()));
    }

    record ServiceRunArgs(Integer port, boolean bundle) {
    }

    record ServiceDescriptor(String name, Path systemd, Path binary, String root, String port, String log) {
    }
}
