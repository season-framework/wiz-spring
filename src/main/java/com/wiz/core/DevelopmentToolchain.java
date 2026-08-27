package com.wiz.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.tools.ToolProvider;

/** Verifies the local build tools required by every generated project. */
public final class DevelopmentToolchain {

    public static final int MINIMUM_JAVA = 21;
    public static final String NODE_REQUIREMENT = "^22.22.3 || ^24.15.0 || ^26.0.0";
    public static final String NPM_REQUIREMENT = ">=10.0.0";

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAXIMUM_OUTPUT_BYTES = 4096;
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)([-+].*)?$");

    private final JavaProbe javaProbe;
    private final CommandProbe commandProbe;

    public DevelopmentToolchain() {
        this(DevelopmentToolchain::currentJava, DevelopmentToolchain::runCommand);
    }

    DevelopmentToolchain(JavaProbe javaProbe, CommandProbe commandProbe) {
        this.javaProbe = Objects.requireNonNull(javaProbe, "javaProbe");
        this.commandProbe = Objects.requireNonNull(commandProbe, "commandProbe");
    }

    public Report verify() {
        ArrayList<String> problems = new ArrayList<>();
        JavaInstallation java = inspectJava(problems);
        ToolInstallation node = inspectCommand("Node.js", nodeCommand(), NODE_REQUIREMENT, problems);
        ToolInstallation npm = inspectCommand("npm", npmCommand(), NPM_REQUIREMENT, problems);

        if (node.available() && !node.version().isStable()) {
            problems.add("Node.js prerelease versions are not supported: " + node.rawVersion());
        } else if (node.available() && !supportsNode(node.version())) {
            problems.add("Node.js " + node.version() + " is unsupported (required: " + NODE_REQUIREMENT + ")");
        }
        if (npm.available() && !npm.version().isStable()) {
            problems.add("npm prerelease versions are not supported: " + npm.rawVersion());
        } else if (npm.available() && npm.version().compareTo(new Version(10, 0, 0, null)) < 0) {
            problems.add("npm " + npm.version() + " is too old (required: " + NPM_REQUIREMENT + ")");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Development toolchain requirements not met:\n- "
                    + String.join("\n- ", problems)
                    + "\nRequired: JDK " + MINIMUM_JAVA + "+, Node.js " + NODE_REQUIREMENT
                    + ", npm " + NPM_REQUIREMENT
                    + ". Install supported tools and retry; no project files were created.");
        }
        return new Report(java.displayVersion(), node.version().toString(), npm.version().toString());
    }

    private JavaInstallation inspectJava(List<String> problems) {
        JavaInstallation java;
        try {
            java = javaProbe.inspect();
        } catch (RuntimeException exception) {
            problems.add("Java could not be inspected: " + safeMessage(exception));
            return new JavaInstallation("unknown", 0, false);
        }
        if (java.feature() < MINIMUM_JAVA) {
            problems.add("Java " + java.displayVersion() + " is too old (required: JDK " + MINIMUM_JAVA + "+)");
        }
        if (!java.compilerAvailable()) {
            problems.add("javac was not found; a full JDK " + MINIMUM_JAVA + "+ is required, not a JRE");
        }
        return java;
    }

    private ToolInstallation inspectCommand(
            String label,
            List<String> command,
            String requirement,
            List<String> problems) {
        CommandResult result;
        try {
            result = commandProbe.execute(command);
        } catch (IOException exception) {
            problems.add(label + " was not found on PATH (required: " + requirement + ")");
            return ToolInstallation.unavailable();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            problems.add(label + " version check was interrupted");
            return ToolInstallation.unavailable();
        } catch (TimeoutException exception) {
            problems.add(label + " version check timed out after " + COMMAND_TIMEOUT.toSeconds() + " seconds");
            return ToolInstallation.unavailable();
        }
        String output = result.output().strip();
        if (result.exitCode() != 0) {
            problems.add(label + " version check failed with exit code " + result.exitCode()
                    + (output.isEmpty() ? "" : ": " + oneLine(output)));
            return ToolInstallation.unavailable();
        }
        try {
            return new ToolInstallation(output, Version.parse(output), true);
        } catch (IllegalArgumentException exception) {
            problems.add(label + " returned an unrecognized version: " + (output.isEmpty() ? "<empty>" : oneLine(output)));
            return ToolInstallation.unavailable();
        }
    }

    private static boolean supportsNode(Version version) {
        if (version.major() == 22) {
            return version.compareTo(new Version(22, 22, 3, null)) >= 0;
        }
        if (version.major() == 24) {
            return version.compareTo(new Version(24, 15, 0, null)) >= 0;
        }
        return version.major() == 26;
    }

    private static JavaInstallation currentJava() {
        Runtime.Version runtime = Runtime.version();
        return new JavaInstallation(runtime.toString(), runtime.feature(), ToolProvider.getSystemJavaCompiler() != null);
    }

    private static CommandResult runCommand(List<String> command)
            throws IOException, InterruptedException, TimeoutException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> output = executor.submit(() -> process.getInputStream().readNBytes(MAXIMUM_OUTPUT_BYTES + 1));
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new TimeoutException(String.join(" ", command));
            }
            byte[] bytes;
            try {
                bytes = output.get(1, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("Failed to read tool version output", cause);
            } catch (TimeoutException exception) {
                throw new IOException("Timed out while reading tool version output", exception);
            }
            if (bytes.length > MAXIMUM_OUTPUT_BYTES) {
                throw new IOException("Tool version output exceeded " + MAXIMUM_OUTPUT_BYTES + " bytes");
            }
            return new CommandResult(process.exitValue(), new String(bytes, StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static List<String> nodeCommand() {
        return List.of("node", "--version");
    }

    private static List<String> npmCommand() {
        String operatingSystem = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return List.of(operatingSystem.contains("win") ? "npm.cmd" : "npm", "--version");
    }

    private static String oneLine(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : oneLine(message);
    }

    public record Report(String javaVersion, String nodeVersion, String npmVersion) {
        public String summary() {
            return "Java " + javaVersion + ", Node.js " + nodeVersion + ", npm " + npmVersion;
        }
    }

    record JavaInstallation(String displayVersion, int feature, boolean compilerAvailable) {
    }

    record CommandResult(int exitCode, String output) {
    }

    record ToolInstallation(String rawVersion, Version version, boolean available) {
        static ToolInstallation unavailable() {
            return new ToolInstallation("", new Version(0, 0, 0, null), false);
        }
    }

    record Version(int major, int minor, int patch, String suffix) implements Comparable<Version> {
        static Version parse(String raw) {
            Matcher matcher = VERSION_PATTERN.matcher(raw.strip());
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Invalid semantic version: " + raw);
            }
            return new Version(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    matcher.group(4));
        }

        boolean isStable() {
            return suffix == null || suffix.startsWith("+");
        }

        @Override
        public int compareTo(Version other) {
            int majorComparison = Integer.compare(major, other.major);
            if (majorComparison != 0) {
                return majorComparison;
            }
            int minorComparison = Integer.compare(minor, other.minor);
            return minorComparison != 0 ? minorComparison : Integer.compare(patch, other.patch);
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch + (suffix == null ? "" : suffix);
        }
    }

    @FunctionalInterface
    interface JavaProbe {
        JavaInstallation inspect();
    }

    @FunctionalInterface
    interface CommandProbe {
        CommandResult execute(List<String> command) throws IOException, InterruptedException, TimeoutException;
    }
}
