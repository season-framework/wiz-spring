package com.wiz.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class ProcessLogManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void rotatesAtTheSizeLimitAndBoundsArchiveCount() throws Exception {
        Path log = tempDir.resolve("server.log");
        try (ProcessLogManager.RotatingFileOutputStream output =
                new ProcessLogManager.RotatingFileOutputStream(log, 5, 2)) {
            output.write("abcdefghijklmnop".getBytes(StandardCharsets.UTF_8));
        }

        assertEquals("p", Files.readString(log));
        assertEquals("klmno", Files.readString(tempDir.resolve("server.log.1")));
        assertEquals("fghij", Files.readString(tempDir.resolve("server.log.2")));
        assertFalse(Files.exists(tempDir.resolve("server.log.3")));
    }

    @Test
    void rotatesAnOversizedExistingLogBeforeAppending() throws Exception {
        Path log = tempDir.resolve("server.log");
        Files.writeString(log, "existing");

        try (ProcessLogManager.RotatingFileOutputStream output =
                new ProcessLogManager.RotatingFileOutputStream(log, 5, 2)) {
            output.write('x');
        }

        assertEquals("x", Files.readString(log));
        assertEquals("existing", Files.readString(tempDir.resolve("server.log.1")));
    }

    @Test
    void rejectsWritesAfterClose() throws Exception {
        Path log = tempDir.resolve("server.log");
        ProcessLogManager.RotatingFileOutputStream output =
                new ProcessLogManager.RotatingFileOutputStream(log, 5, 2);
        output.close();

        assertThrows(IOException.class, () -> output.write('x'));
    }

    @Test
    void claimsAnIdenticalSpringFileNameForTheProcessLogger() throws Exception {
        Path log = tempDir.resolve("logs/server.log");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("log.directory", log.getParent().toString())
                .withProperty("logging.file.name", "${log.directory}/server.log");

        assertTrue(ProcessLogManager.claimSpringFileTarget(environment, log));
        assertEquals("", environment.getProperty("logging.file.name"));
        assertEquals("", environment.getProperty("logging.file.path"));
    }

    @Test
    void claimsTheSpringLogCreatedByAnIdenticalLoggingPath() throws Exception {
        Path logDirectory = tempDir.resolve("logs");
        Path log = logDirectory.resolve("spring.log");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("logging.file.path", logDirectory.toString());

        assertTrue(ProcessLogManager.claimSpringFileTarget(environment, log));
        assertEquals("", environment.getProperty("logging.file.name"));
        assertEquals("", environment.getProperty("logging.file.path"));
    }

    @Test
    void preservesASeparateUserConfiguredSpringLogFile() throws Exception {
        Path processLog = tempDir.resolve("logs/process.log");
        Path springLog = tempDir.resolve("logs/spring.log");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("logging.file.name", springLog.toString());

        assertFalse(ProcessLogManager.claimSpringFileTarget(environment, processLog));
        assertEquals(springLog.toString(), environment.getProperty("logging.file.name"));
    }
}
