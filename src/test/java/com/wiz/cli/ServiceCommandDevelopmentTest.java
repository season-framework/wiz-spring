package com.wiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

class ServiceCommandDevelopmentTest {

    @TempDir
    Path tempDir;

    @Test
    void installDefaultsToLiveDevelopmentFromTheProjectRoot() throws Exception {
        Path project = createDevelopmentProject();
        Path npm = executable("fake-npm", "#!/bin/sh\nexit 0\n");
        Path java = executable("fake-java", "#!/bin/sh\nexit 0\n");

        CommandResult result = execute(
                "service", "install", "demo",
                "--root", project.toString(),
                "--npm", npm.toString(),
                "--java", java.toString(),
                "--port", "18081",
                "--allow-root",
                "--dry-run",
                "--systemd-dir", tempDir.resolve("systemd").toString(),
                "--bin-dir", tempDir.resolve("bin").toString());

        assertEquals(0, result.exitCode(), result.error());
        String launcher = result.output();
        assertTrue(launcher.contains("# wiz.service.mode=development"));
        assertTrue(launcher.contains("# wiz.service.root=" + project));
        assertTrue(launcher.contains("# wiz.service.profiles=dev"));
        assertTrue(launcher.contains("# wiz.service.env-file=" + project.resolve(".env")));
        assertTrue(launcher.contains("EnvironmentFile=" + project.resolve(".env")));
        assertFalse(launcher.contains("EnvironmentFile=-"));
        assertTrue(launcher.contains("export SPRING_PROFILES_ACTIVE=\"${SPRING_PROFILES_ACTIVE:-dev}\""));
        assertTrue(launcher.contains("export SERVER_PORT='18081'"));
        assertTrue(launcher.contains("exec '" + npm + "' run dev"));
        assertFalse(launcher.contains(" -jar "));
        assertFalse(launcher.contains("# wiz.service.bundle="));
    }

    @Test
    void installedDevelopmentLauncherPreservesRuntimeEnvironmentAndStartsImmediately() throws Exception {
        Path project = createDevelopmentProject();
        Path npm = executable("recording-npm", """
                #!/bin/sh
                printf 'ARGS=%s\n' "$*"
                printf 'PROFILE=%s\n' "$SPRING_PROFILES_ACTIVE"
                printf 'PORT=%s\n' "$SERVER_PORT"
                printf 'ROOT=%s\n' "$PWD"
                """);
        Path java = executable("fake-java", "#!/bin/sh\nexit 0\n");
        Path systemd = Files.createDirectories(tempDir.resolve("installed-systemd"));
        Path bin = Files.createDirectories(tempDir.resolve("installed-bin"));
        Path systemctlCalls = tempDir.resolve("systemctl.calls");
        Path systemctl = executable("fake-systemctl", "#!/bin/sh\nprintf '%s\\n' \"$*\" >> '"
                + systemctlCalls + "'\n");

        CommandResult result = execute(
                "service", "install", "demo",
                "--root", project.toString(),
                "--npm", npm.toString(),
                "--java", java.toString(),
                "--profiles", "dev,local",
                "--port", "18082",
                "--allow-root",
                "--systemd-dir", systemd.toString(),
                "--bin-dir", bin.toString(),
                "--systemctl", systemctl.toString());

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("daemon-reload\nenable --now wiz.demo\n", Files.readString(systemctlCalls));
        assertTrue(result.output().contains("Service installed: wiz.demo (development)"));

        Path launcher = bin.resolve("wiz.demo");
        Process process = new ProcessBuilder(launcher.toString()).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor());
        assertEquals("ARGS=run dev\n"
                + "PROFILE=dev,local\n"
                + "PORT=18082\n"
                + "ROOT=" + project + "\n", output);
        assertTrue(Files.readString(systemd.resolve("wiz.demo.service"))
                .contains("ExecStart=" + launcher));
        assertFalse(Files.readString(launcher).contains("wiz-spring"));
    }

    @Test
    void explicitEnvironmentFileIsRequiredAndRecordedInTheUnit() throws Exception {
        Path project = createDevelopmentProject();
        Path environment = project.resolve("config directory/service %.env");
        Files.createDirectories(environment.getParent());
        Files.writeString(environment, "APP_API_PREFIX=/configured\n");
        Path npm = executable("env-npm", "#!/bin/sh\nexit 0\n");
        Path java = executable("env-java", "#!/bin/sh\nexit 0\n");

        CommandResult result = execute(
                "service", "install", "env-demo",
                "--root", project.toString(),
                "--env-file", "config directory/service %.env",
                "--npm", npm.toString(),
                "--java", java.toString(),
                "--allow-root",
                "--dry-run");

        assertEquals(0, result.exitCode(), result.error());
        assertTrue(result.output().contains("# wiz.service.env-file=" + environment));
        String encodedEnvironment = environment.toString()
                .replace(" ", "\\x20")
                .replace("%", "\\x25");
        assertTrue(result.output().contains("EnvironmentFile=" + encodedEnvironment));
        assertFalse(result.output().contains("EnvironmentFile=-" + encodedEnvironment));

        CommandResult missing = execute(
                "service", "install", "env-missing",
                "--root", project.toString(),
                "--env-file", "config/missing.env",
                "--npm", npm.toString(),
                "--java", java.toString(),
                "--allow-root",
                "--dry-run");
        assertEquals(1, missing.exitCode());
        assertTrue(missing.error().contains("Explicit environment file does not exist"), missing.error());
    }

    @Test
    void defaultModeRejectsDirectoriesThatAreNotGeneratedProjects() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("not-a-project"));
        Path npm = executable("missing-project-npm", "#!/bin/sh\nexit 0\n");
        Path java = executable("missing-project-java", "#!/bin/sh\nexit 0\n");

        CommandResult result = execute(
                "service", "install", "demo",
                "--root", project.toString(),
                "--npm", npm.toString(),
                "--java", java.toString(),
                "--allow-root",
                "--dry-run");

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("Development service requires a generated WIZ Spring project"),
                result.error());
        assertTrue(result.error().contains("Pass --root"), result.error());
        assertTrue(result.error().contains("use --production"), result.error());
    }

    @Test
    void artifactOverrideCannotSilentlySwitchDevelopmentIntoProduction() throws Exception {
        Path project = createDevelopmentProject();

        CommandResult result = execute(
                "service", "install", "demo",
                "--root", project.toString(),
                "--artifact", "app/application.jar",
                "--allow-root",
                "--dry-run");

        assertEquals(1, result.exitCode());
        assertTrue(result.error().contains("--artifact requires --production or --bundle"), result.error());
    }

    private Path createDevelopmentProject() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(project.resolve("package.json"), """
                {
                  "name": "demo",
                  "scripts": {
                    "dev": "node scripts/dev.mjs"
                  }
                }
                """);
        Files.writeString(project.resolve(".env"), "SERVER_PORT=8080\nSPRING_PROFILES_ACTIVE=dev\n");
        Path scripts = Files.createDirectories(project.resolve("scripts"));
        Files.writeString(scripts.resolve("dev.mjs"), "// managed development entry point\n");
        Files.createDirectories(project.resolve("src/main/java"));
        Path wrapper = project.resolve("mvnw");
        Files.writeString(wrapper, "#!/bin/sh\nexit 0\n");
        wrapper.toFile().setExecutable(true, false);
        return project.toRealPath();
    }

    private Path executable(String name, String contents) throws Exception {
        Path executable = tempDir.resolve(name);
        Files.writeString(executable, contents);
        executable.toFile().setExecutable(true, false);
        return executable.toAbsolutePath().normalize();
    }

    private CommandResult execute(String... args) {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine command = new CommandLine(new WizCommand());
        command.setOut(new PrintWriter(output));
        command.setErr(new PrintWriter(error));
        int exitCode = command.execute(args);
        return new CommandResult(exitCode, output.toString(), error.toString());
    }

    private record CommandResult(int exitCode, String output, String error) {
    }
}
