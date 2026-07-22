package com.wiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WizSpringLauncherTest {

    @TempDir
    Path tempDir;

    @Test
    void reportsMissingAndEmptyRuntimeJarWithBuildGuidance() throws Exception {
        Path missing = tempDir.resolve("missing.jar");
        ProcessResult missingResult = launch(missing, systemPath(), "--version");

        assertEquals(1, missingResult.exitCode());
        assertTrue(missingResult.output().contains("runtime jar not found"));
        assertTrue(missingResult.output().contains("./mvnw clean package"));

        Path empty = tempDir.resolve("empty.jar");
        Files.createFile(empty);
        ProcessResult emptyResult = launch(empty, systemPath(), "--version");

        assertEquals(1, emptyResult.exitCode());
        assertTrue(emptyResult.output().contains("runtime jar is empty"));
    }

    @Test
    void reportsMissingJavaBeforeLaunchingRuntime() throws Exception {
        Path runtime = tempDir.resolve("runtime.jar");
        Files.write(runtime, new byte[] {1});
        Path emptyPath = tempDir.resolve("empty-path");
        Files.createDirectories(emptyPath);

        ProcessResult result = launch(runtime, emptyPath.toString(), "run");

        assertEquals(1, result.exitCode());
        assertTrue(result.output().contains("Java was not found on PATH"));
        assertTrue(result.output().contains("Java 21"));
    }

    @Test
    void forwardsRuntimeJarAndArgumentsToJava() throws Exception {
        Path runtime = tempDir.resolve("runtime.jar");
        Files.write(runtime, new byte[] {1});
        Path bin = tempDir.resolve("bin");
        Files.createDirectories(bin);
        Path calls = tempDir.resolve("java.calls");
        Path java = bin.resolve("java");
        Files.writeString(java, "#!/bin/sh\nprintf '%s\\n' \"$*\" > '" + calls + "'\n");
        java.toFile().setExecutable(true, false);

        ProcessResult result = launch(runtime, bin.toString(), "run", "--dry-run");

        assertEquals(0, result.exitCode());
        assertEquals("-jar " + runtime + " run --dry-run" + System.lineSeparator(), Files.readString(calls));
    }

    private ProcessResult launch(Path runtime, String path, String... args) throws Exception {
        Path launcher = Path.of("wiz-spring-cli").toAbsolutePath().normalize();
        java.util.ArrayList<String> argv = new java.util.ArrayList<>();
        argv.add("/bin/sh");
        argv.add(launcher.toString());
        argv.addAll(java.util.List.of(args));
        ProcessBuilder builder = new ProcessBuilder(argv);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", path);
        environment.put("WIZ_RUNTIME_JAR", runtime.toString());
        Process process = builder.redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return new ProcessResult(process.waitFor(), output);
    }

    private String systemPath() {
        return System.getenv().getOrDefault("PATH", "/usr/bin:/bin");
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
