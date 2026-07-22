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
        ServiceCommand.Logs.class,
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

        @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
        private Path root;

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
            Path rootPath = WorkspaceRootResolver.resolve(root, "service install");
            Path logPath = log == null ? logDir.resolve(shortName) : log.toAbsolutePath().normalize();
            Path commandPath = binDir.resolve(serviceName);
            Path servicePath = systemdDir.resolve(serviceName + ".service");
            requireSystemdExecutablePath(commandPath);
            requireSingleLine("Service definition path", servicePath.toString());
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

        @Option(names = "--systemctl", hidden = true)
        private Path systemctl = Path.of("systemctl");

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
            runSystemctl(systemctl, "stop", serviceName);
            runSystemctl(systemctl, "disable", serviceName);
            Files.deleteIfExists(commandPath);
            Files.deleteIfExists(servicePath);
            runSystemctl(systemctl, "daemon-reload");
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

    @Command(name = "logs", aliases = {"log"}, mixinStandardHelpOptions = true,
            description = "Show recent WIZ service journal logs.")
    static class Logs implements Callable<Integer> {
        @Spec
        private CommandSpec spec;

        @Parameters(index = "0", description = "Service name.")
        private String name;

        @Option(names = {"-n", "--lines"}, defaultValue = "200", description = "Number of recent journal lines (1-10000).")
        private int lines;

        @Option(names = {"-f", "--follow"}, description = "Continue following new journal entries.")
        private boolean follow;

        @Option(names = "--journalctl", hidden = true)
        private Path journalctl = Path.of("journalctl");

        @Option(names = "--bin-dir", hidden = true)
        private Path binDir = DEFAULT_BIN_DIR;

        @Option(names = "--log-dir", hidden = true)
        private Path logDir = DEFAULT_LOG_DIR;

        @Override
        public Integer call() throws Exception {
            ensureLinux();
            if (lines < 1 || lines > 10_000) {
                throw new IllegalArgumentException("Journal line count must be between 1 and 10000");
            }
            String normalized = serviceName(name);
            String shortName = shortServiceName(normalized);
            String applicationLog = metadata(binDir.resolve(normalized))
                    .getOrDefault("log", logDir.resolve(shortName).toString());
            var out = spec.commandLine().getOut();
            out.println("Service: " + normalized);
            out.println("Application log: " + applicationLog);
            out.flush();

            ArrayList<String> args = new ArrayList<>();
            args.add("--unit");
            args.add(normalized);
            args.add("--lines");
            args.add(String.valueOf(lines));
            args.add("--no-pager");
            if (follow) {
                args.add("--follow");
            }
            return runCommand(journalctl, args);
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
        String normalized = requireSingleLine("Service name", name).toLowerCase(Locale.ROOT);
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
        String normalized = requireSingleLine("Service command", command).trim();
        if (!normalized.matches("[A-Za-z0-9_./+~-]+")) {
            throw new IllegalArgumentException("Service command contains unsupported shell characters: " + command);
        }
        return normalized;
    }

    private static String script(String serviceName, String command, Path root, Integer port, boolean bundle, Path log) {
        String safeServiceName = requireSingleLine("Service name", serviceName);
        String safeCommand = requireSingleLine("Service command", command);
        String rootValue = requireSingleLine("Workspace root", root.toString());
        String logValue = requireSingleLine("Log path", log.toString());
        return "#!/bin/bash\n"
                + metadataLine("name", shortServiceName(safeServiceName))
                + metadataLine("root", rootValue)
                + metadataLine("port", port == null ? "config" : String.valueOf(port))
                + metadataLine("bundle", String.valueOf(bundle))
                + metadataLine("log", logValue)
                + metadataLine("command", safeCommand)
                + "export PS1=${PS1:-wiz-service}\n"
                + "shopt -s expand_aliases\n"
                + "cd " + shell(rootValue) + "\n"
                + "if ! type " + shell(safeCommand) + " >/dev/null 2>&1 && [ -r /root/.bashrc ]; then source /root/.bashrc; fi\n"
                + "type " + shell(safeCommand) + " >/dev/null 2>&1 || { echo " + shell(safeCommand + " command not found; install it on PATH or use service install --command /absolute/path") + " >&2; exit 127; }\n"
                + "exec " + safeCommand + " run"
                + (port == null ? "" : " --port " + port)
                + (bundle ? " --bundle" : "")
                + " --log " + shell(logValue)
                + "\n";
    }

    private static String unit(String serviceName, Path commandPath) {
        String safeServiceName = requireSingleLine("Service name", serviceName);
        String execStart = requireSystemdExecutablePath(commandPath);
        return "[Unit]\n"
                + "Description=" + safeServiceName + "\n"
                + "Wants=network-online.target\n"
                + "After=network-online.target\n\n"
                + "[Service]\n"
                + "Type=simple\n"
                + "Environment=\"PATH=/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin\"\n"
                + "ExecStart=" + execStart + "\n"
                + "Restart=on-failure\n"
                + "RestartSec=5s\n"
                + "TimeoutStopSec=30s\n"
                + "SuccessExitStatus=143\n"
                + "UMask=0027\n\n"
                + "[Install]\n"
                + "WantedBy=multi-user.target\n";
    }

    private static int runSystemctl(String... args) throws IOException, InterruptedException {
        return runSystemctl(Path.of("systemctl"), args);
    }

    private static int runSystemctl(Path systemctl, String... args) throws IOException, InterruptedException {
        return runCommand(systemctl, List.of(args));
    }

    private static int runCommand(Path command, List<String> args) throws IOException, InterruptedException {
        ArrayList<String> argv = new ArrayList<>();
        argv.add(requireSingleLine("Command path", command.toString()));
        argv.addAll(args);
        Process process = new ProcessBuilder(argv).inheritIO().start();
        return process.waitFor();
    }

    private static String shell(String value) {
        return "'" + requireSingleLine("Shell value", value).replace("'", "'\"'\"'") + "'";
    }

    private static String metadataLine(String key, String value) {
        if (key == null || !key.matches("[a-z][a-z0-9.-]*")) {
            throw new IllegalArgumentException("Invalid service metadata key");
        }
        return "# wiz.service." + key + "="
                + requireSingleLine("Service metadata '" + key + "'", value)
                + "\n";
    }

    private static String requireSingleLine(String label, String value) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        if (!isSafeSingleLine(value)) {
            throw new IllegalArgumentException(label + " must be a single line without control characters");
        }
        return value;
    }

    private static boolean isSafeSingleLine(String value) {
        return value.codePoints().noneMatch(codePoint -> Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.LINE_SEPARATOR
                || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR);
    }

    private static String requireSystemdExecutablePath(Path path) {
        String value = requireSingleLine("Service executable path", path == null ? null : path.toString());
        if (!path.isAbsolute() || !value.matches("/[A-Za-z0-9_./+~-]+")) {
            throw new IllegalArgumentException("Service executable path must be an absolute path without whitespace or systemd expansion characters");
        }
        return value;
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
                if (!key.isBlank() && !value.isBlank() && isSafeSingleLine(key) && isSafeSingleLine(value)) {
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
