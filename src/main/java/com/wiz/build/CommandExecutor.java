package com.wiz.build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.wiz.security.SecretMasker;

public class CommandExecutor {

    private static final int DEFAULT_OUTPUT_CAP_BYTES = 64 * 1024;

    public CommandResult run(String phase, Path workspaceRoot, Path cwd, List<String> argv, Duration timeout) throws IOException, InterruptedException {
        return run(phase, workspaceRoot, cwd, argv, timeout, DEFAULT_OUTPUT_CAP_BYTES);
    }

    public CommandResult run(String phase, Path workspaceRoot, Path cwd, List<String> argv, Duration timeout, int outputCapBytes) throws IOException, InterruptedException {
        return run(phase, workspaceRoot, cwd, argv, timeout, outputCapBytes, BuildLogger.quiet());
    }

    public CommandResult run(String phase, Path workspaceRoot, Path cwd, List<String> argv, Duration timeout, int outputCapBytes, BuildLogger logger) throws IOException, InterruptedException {
        if (argv == null || argv.isEmpty() || argv.get(0).isBlank()) {
            throw new IllegalArgumentException("Command argv must not be empty");
        }
        BuildLogger buildLogger = logger == null ? BuildLogger.quiet() : logger;
        Path normalizedRoot = workspaceRoot.toAbsolutePath().normalize();
        Path normalizedCwd = cwd.toAbsolutePath().normalize();
        if (!normalizedCwd.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Command cwd escapes workspace root");
        }

        List<String> resolvedArgv = new ArrayList<>(argv);
        resolvedArgv.set(0, resolveExecutable(normalizedRoot, normalizedCwd, argv.get(0)).toString());

        ProcessBuilder builder = new ProcessBuilder(resolvedArgv);
        builder.directory(normalizedCwd.toFile());
        builder.redirectErrorStream(true);
        configureEnvironment(builder.environment());

        long started = System.nanoTime();
        Process process = builder.start();
        CappedOutput output = new CappedOutput(Math.max(1, outputCapBytes));
        Thread reader = Thread.ofVirtual().start(() -> copyOutput(process.getInputStream(), output, buildLogger));

        boolean finished = process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        boolean timedOut = !finished;
        if (timedOut) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
        }
        try {
            reader.join(1000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }

        long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        int exitCode = timedOut ? -1 : process.exitValue();
        return new CommandResult(
                phase,
                List.copyOf(argv),
                normalizedCwd,
                exitCode,
                durationMillis,
                timedOut,
                output.capped(),
                SecretMasker.mask(output.text()));
    }

    private Path resolveExecutable(Path workspaceRoot, Path cwd, String command) {
        Path commandPath = command.contains("/") || command.contains("\\") ? cwd.resolve(command).toAbsolutePath().normalize() : null;
        String fileName = commandPath == null ? command : commandPath.getFileName().toString();
        if (fileName.equals("mvn")) {
            if (commandPath != null) {
                throw new IllegalArgumentException("mvn must be invoked by command name");
            }
            return MavenExecutableResolver.require(workspaceRoot);
        }
        if (fileName.equals("node") || fileName.equals("npm")) {
            if (commandPath != null) {
                throw new IllegalArgumentException(fileName + " must be invoked by command name");
            }
            return findOnPath(fileName).orElseThrow(() -> new IllegalArgumentException(fileName + " is not available on PATH"));
        }
        if (fileName.equals("ng")) {
            Path localNg = cwd.resolve("node_modules/.bin/ng").toAbsolutePath().normalize();
            if (commandPath == null) {
                commandPath = localNg;
            }
            if (!commandPath.equals(localNg) || !commandPath.startsWith(workspaceRoot)) {
                throw new IllegalArgumentException("ng must be project-local node_modules/.bin/ng");
            }
            if (!Files.isRegularFile(commandPath)) {
                throw new IllegalArgumentException("Project-local ng executable is missing: " + localNg);
            }
            return commandPath;
        }
        throw new IllegalArgumentException("Command is not allowed: " + command);
    }

    private Optional<Path> findOnPath(String command) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String entry : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            Path candidate = Path.of(entry).resolve(command).toAbsolutePath().normalize();
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private void configureEnvironment(Map<String, String> environment) {
        String path = System.getenv("PATH");
        String home = System.getenv("HOME");
        String javaHome = System.getenv("JAVA_HOME");
        String mavenOpts = System.getenv("MAVEN_OPTS");
        environment.clear();
        if (path != null && !path.isBlank()) {
            environment.put("PATH", path);
        }
        if (home != null && !home.isBlank()) {
            environment.put("HOME", home);
        }
        if (javaHome != null && !javaHome.isBlank()) {
            environment.put("JAVA_HOME", javaHome);
        }
        if (mavenOpts != null && !mavenOpts.isBlank()) {
            environment.put("MAVEN_OPTS", mavenOpts);
        }
        environment.put("CI", "true");
        environment.put("NO_COLOR", "1");
    }

    private void copyOutput(InputStream input, CappedOutput output, BuildLogger logger) {
        try (input) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, read);
                logger.output(SecretMasker.mask(new String(buffer, 0, read, StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) {
        }
    }

    private static final class CappedOutput {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final int cap;
        private boolean capped;

        private CappedOutput(int cap) {
            this.cap = cap;
        }

        synchronized void write(byte[] bytes, int length) {
            int remaining = cap - buffer.size();
            if (remaining > 0) {
                buffer.write(bytes, 0, Math.min(remaining, length));
            }
            if (length > remaining) {
                capped = true;
            }
        }

        synchronized boolean capped() {
            return capped;
        }

        synchronized String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
