package com.wiz.logging;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.logging.LogFile;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Captures the complete process console in a bounded, rotating log file. */
public final class ProcessLogManager {

    public static final long DEFAULT_MAX_FILE_SIZE = 10L * 1024L * 1024L;
    public static final int DEFAULT_MAX_ARCHIVES = 14;

    private static final Object INSTALL_LOCK = new Object();
    private static final String SPRING_FILE_CLAIM = "wizProcessLogFileClaim";
    private static Path installedPath;

    private ProcessLogManager() {
    }

    public static void install(String log) {
        if (log == null || log.isBlank()) {
            return;
        }
        Path path = Path.of(log).toAbsolutePath().normalize();
        synchronized (INSTALL_LOCK) {
            if (installedPath != null) {
                if (installedPath.equals(path)) {
                    return;
                }
                throw new IllegalStateException("WIZ process logging is already configured for " + installedPath);
            }
            install(path, DEFAULT_MAX_FILE_SIZE, DEFAULT_MAX_ARCHIVES);
            installedPath = path;
        }
    }

    /**
     * Makes the {@code --log} target a single-writer file before Spring Boot configures Logback.
     * A distinct user-configured Spring log file is deliberately left untouched.
     */
    public static void claimSpringFileTarget(SpringApplication application, String log) {
        if (application == null || log == null || log.isBlank()) {
            return;
        }
        Path processLog = Path.of(log).toAbsolutePath().normalize();
        application.addListeners(new SpringFileCollisionGuard(processLog));
    }

    static boolean claimSpringFileTarget(ConfigurableEnvironment environment, Path processLog) {
        LogFile springLog = LogFile.get(environment);
        if (springLog == null || !sameTarget(processLog, Path.of(springLog.toString()))) {
            return false;
        }
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put(LogFile.FILE_NAME_PROPERTY, "");
        claim.put(LogFile.FILE_PATH_PROPERTY, "");
        environment.getPropertySources().addFirst(new MapPropertySource(SPRING_FILE_CLAIM, claim));
        return true;
    }

    private static boolean sameTarget(Path first, Path second) {
        Path firstComparable = comparablePath(first);
        Path secondComparable = comparablePath(second);
        if (firstComparable.equals(secondComparable)) {
            return true;
        }
        try {
            return Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second);
        } catch (IOException exception) {
            return false;
        }
    }

    /** Resolves existing parents so aliases through a symlinked log directory also collide. */
    private static Path comparablePath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        ArrayDeque<Path> missing = new ArrayDeque<>();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            missing.addFirst(existing.getFileName());
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        try {
            Path resolved = existing.toRealPath();
            for (Path element : missing) {
                resolved = resolved.resolve(element);
            }
            return resolved.normalize();
        } catch (IOException exception) {
            return absolute;
        }
    }

    private static void install(Path path, long maxFileSize, int maxArchives) {
        try {
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            RotatingFileOutputStream file = new RotatingFileOutputStream(path, maxFileSize, maxArchives);
            System.setOut(new PrintStream(new TeeOutputStream(originalOut, file), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new TeeOutputStream(originalErr, file), true, StandardCharsets.UTF_8));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> closeQuietly(file), "wiz-process-log-close"));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to open WIZ log file: " + path, exception);
        }
    }

    private static void closeQuietly(OutputStream output) {
        try {
            output.close();
        } catch (IOException ignored) {
            // The process is already stopping; there is no reliable secondary log target.
        }
    }

    private static final class SpringFileCollisionGuard
            implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

        private final Path processLog;

        private SpringFileCollisionGuard(Path processLog) {
            this.processLog = processLog;
        }

        @Override
        public int getOrder() {
            // Config data is available at +10 and Logback is initialized at +20.
            return ConfigDataEnvironmentPostProcessor.ORDER + 1;
        }

        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            if (claimSpringFileTarget(event.getEnvironment(), processLog)) {
                System.err.println("Spring logging.file.name/path targets --log; "
                        + "WIZ process logging exclusively owns " + processLog + ".");
            }
        }
    }

    static final class RotatingFileOutputStream extends OutputStream {

        private final Path path;
        private final long maxFileSize;
        private final int maxArchives;
        private OutputStream output;
        private long size;
        private boolean closed;

        RotatingFileOutputStream(Path path, long maxFileSize, int maxArchives) throws IOException {
            if (maxFileSize < 1) {
                throw new IllegalArgumentException("Maximum log file size must be positive");
            }
            if (maxArchives < 1) {
                throw new IllegalArgumentException("Log archive count must be positive");
            }
            this.path = path.toAbsolutePath().normalize();
            this.maxFileSize = maxFileSize;
            this.maxArchives = maxArchives;
            Path parent = this.path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.isDirectory(this.path)) {
                throw new IOException("Log path is a directory: " + this.path);
            }
            this.size = Files.isRegularFile(this.path) ? Files.size(this.path) : 0L;
            if (size >= maxFileSize) {
                rotate();
            } else {
                open();
            }
        }

        @Override
        public synchronized void write(int value) throws IOException {
            byte[] single = {(byte) value};
            write(single, 0, 1);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            checkBounds(bytes, offset, length);
            ensureOpen();
            int cursor = offset;
            int remaining = length;
            while (remaining > 0) {
                if (size >= maxFileSize) {
                    rotate();
                }
                int count = (int) Math.min(remaining, maxFileSize - size);
                output.write(bytes, cursor, count);
                cursor += count;
                remaining -= count;
                size += count;
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            if (output != null) {
                output.flush();
            }
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            if (output != null) {
                output.close();
                output = null;
            }
        }

        private void rotate() throws IOException {
            if (output != null) {
                output.close();
                output = null;
            }
            Files.deleteIfExists(archive(maxArchives));
            for (int index = maxArchives - 1; index >= 1; index--) {
                Path source = archive(index);
                if (Files.exists(source)) {
                    Files.move(source, archive(index + 1), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (Files.exists(path)) {
                Files.move(path, archive(1), StandardCopyOption.REPLACE_EXISTING);
            }
            size = 0L;
            open();
        }

        private void open() throws IOException {
            output = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        private Path archive(int index) {
            return path.resolveSibling(path.getFileName() + "." + index);
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("Log file is closed: " + path);
            }
        }

        private static void checkBounds(byte[] bytes, int offset, int length) {
            if (bytes == null) {
                throw new NullPointerException("bytes");
            }
            if (offset < 0 || length < 0 || offset > bytes.length - length) {
                throw new IndexOutOfBoundsException();
            }
        }
    }

    private static final class TeeOutputStream extends OutputStream {

        private final OutputStream console;
        private final OutputStream file;

        private TeeOutputStream(OutputStream console, OutputStream file) {
            this.console = console;
            this.file = file;
        }

        @Override
        public void write(int value) throws IOException {
            console.write(value);
            file.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            console.write(bytes, offset, length);
            file.write(bytes, offset, length);
        }

        @Override
        public void flush() throws IOException {
            console.flush();
            file.flush();
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
