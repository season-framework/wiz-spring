package com.wiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wiz.runtime.BuildMarkerService;
import com.wiz.runtime.WizSpringVersion;
import com.wiz.runtime.WorkspaceRuntimePaths;

import picocli.CommandLine;

class WizCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void exposesHelpAndVersion() {
        StringWriter output = new StringWriter();
        CommandLine command = new CommandLine(new WizCommand());
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("--version"));
        assertTrue(output.toString().contains("wiz-spring " + WizSpringVersion.current()));

        output.getBuffer().setLength(0);
        assertEquals(0, command.execute("--help"));
        String help = output.toString();
        assertTrue(help.contains("Usage: wiz-spring"));
        assertTrue(help.contains("automatic Codex"));
        assertFalse(help.contains(System.lineSeparator() + "  codex"));
        assertTrue(help.contains("completion"));
        assertFalse(help.contains("wiz-java"));
    }

    @Test
    void helpMentionsSpringMcpAndAutomaticCodexSetup() {
        CommandLine command = new CommandLine(new WizCommand());
        StringWriter output = new StringWriter();
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("mcp", "--help"));
        String mcpHelp = output.toString();
        assertTrue(mcpHelp.contains("standalone WIZ Spring MCP server"));
        assertTrue(mcpHelp.contains("server name"));
        assertTrue(mcpHelp.contains("'wiz-spring'"));
        assertTrue(mcpHelp.contains("outside the workspace"));
        assertFalse(mcpHelp.contains("<workspace>/.wiz"));

        output.getBuffer().setLength(0);
        assertEquals(0, command.execute("create", "--help"));
        String createHelp = output.toString();
        assertTrue(createHelp.contains("automatic .codex"));
        assertTrue(createHelp.contains("bundled .github setup"));
        assertTrue(createHelp.contains("--runtime-jar"));
        assertTrue(createHelp.contains("WIZ_RUNTIME_JAR"));
    }

    @Test
    void standaloneCodexCommandIsNotAvailable() {
        CommandLine command = new CommandLine(new WizCommand());
        command.setErr(new PrintWriter(new StringWriter()));

        assertEquals(2, command.execute("codex"));
    }

    @Test
    void packageRootAliasIsNotAvailable() throws Exception {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine create = new CommandLine(new WizCommand());
        create.setOut(new PrintWriter(output));
        create.setErr(new PrintWriter(error));

        assertEquals(0, create.execute("create", "--help"));
        assertTrue(output.toString().contains("--package=<packageRoot>"));
        assertFalse(output.toString().contains("--package-root"));

        Path newWorkspace = tempDir.resolve("package-root-alias");
        assertEquals(2, create.execute(
                "create", newWorkspace.toString(), "--package-root", "com.example.alias", "--skip-build"));
        assertFalse(Files.exists(newWorkspace));

        output.getBuffer().setLength(0);
        error.getBuffer().setLength(0);
        Path existingWorkspace = minimalWorkspace("build-package-root-alias");
        CommandLine build = new CommandLine(new WizCommand());
        build.setOut(new PrintWriter(output));
        build.setErr(new PrintWriter(error));

        assertEquals(0, build.execute("build", "--help"));
        assertTrue(output.toString().contains("--package=<packageRoot>"));
        assertFalse(output.toString().contains("--package-root"));
        assertEquals(2, build.execute(
                "build", "--root", existingWorkspace.toString(), "--package-root", "com.example.alias"));
    }

    @Test
    void runDryRunDoesNotStartServer() throws Exception {
        Path workspace = minimalWorkspace("run-workspace");
        Path child = workspace.resolve("src/app/page.dashboard");
        Path log = tempDir.resolve("server.log");
        StringWriter output = new StringWriter();
        CommandLine command = new CommandLine(new WizCommand());
        command.setOut(new PrintWriter(output));

        int exitCode = command.execute("run", "--dry-run", "--root", child.toString(), "--port", "18080", "--bundle", "--profile", "prod", "--log", log.toString());

        assertEquals(0, exitCode);
        String diagnostics = output.toString();
        assertTrue(diagnostics.contains("root=" + workspace));
        assertTrue(diagnostics.contains("host="));
        assertTrue(diagnostics.contains("port=18080"));
        assertTrue(diagnostics.contains("bundle=true"));
        assertTrue(diagnostics.contains("profile=prod"));
        assertTrue(diagnostics.contains("config=optional:"));
        assertTrue(diagnostics.contains("java=" + System.getProperty("java.version")));
        assertTrue(diagnostics.contains("log=" + log.toAbsolutePath().normalize()));
        assertFalse(Files.exists(log), "dry-run must not create the log file");
    }

    @Test
    void runRejectsDirectoryAsLogFile() throws Exception {
        Path workspace = minimalWorkspace("run-log-directory");
        Path logDirectory = tempDir.resolve("logs");
        Files.createDirectories(logDirectory);
        StringWriter error = new StringWriter();
        CommandLine command = new CommandLine(new WizCommand());
        command.setErr(new PrintWriter(error));

        assertEquals(1, command.execute(
                "run", "--dry-run", "--root", workspace.toString(), "--log", logDirectory.toString()));
        assertTrue(error.toString().contains("WIZ log path must be a file"));
    }

    @Test
    void runRejectsWorkspaceWithoutACompletedBundleBeforeOpeningLog() throws Exception {
        Path workspace = minimalWorkspace("unbuilt-run-workspace");
        Path log = tempDir.resolve("unbuilt-server.log");
        StringWriter error = new StringWriter();
        CommandLine command = new CommandLine(new WizCommand());
        command.setErr(new PrintWriter(error));

        assertEquals(1, command.execute("run", "--root", workspace.toString(), "--log", log.toString()));
        assertTrue(error.toString().contains("bundle/.wiz-build.json is missing or invalid"));
        assertTrue(error.toString().contains("wiz-spring build"));
        assertTrue(error.toString().contains("Maven on PATH"));
        assertFalse(Files.exists(log));
    }

    @Test
    void runRejectsBundleBuiltForADifferentJavaPackage() throws Exception {
        Path workspace = minimalWorkspace("package-mismatch-run-workspace");
        Files.createDirectories(workspace.resolve("bundle/src/app"));
        Files.writeString(workspace.resolve("bundle").resolve(BuildMarkerService.MARKER_FILE), """
                {
                  "javaPackageRoot": "com.example.previous",
                  "buildPhases": [ "bundle" ],
                  "runtimeVersion": "%s"
                }
                """.formatted(WizSpringVersion.current()));
        StringWriter error = new StringWriter();
        CommandLine command = new CommandLine(new WizCommand());
        command.setErr(new PrintWriter(error));

        assertEquals(1, command.execute("run", "--root", workspace.toString()));
        assertTrue(error.toString().contains("bundle Java package is com.example.previous"), error.toString());
        assertTrue(error.toString().contains("workspace package is com.wiz.app"), error.toString());
    }

    @Test
    void bundleRejectsWorkspaceAsOutputWithoutDeletingSources() throws Exception {
        Path workspace = minimalWorkspace("unsafe-bundle-output");
        Files.createDirectories(workspace.resolve("bundle/src/app"));
        Path source = workspace.resolve("src/app/page.dashboard/app.json");
        Files.writeString(source, "{}\n");
        StringWriter error = new StringWriter();
        CommandLine command = new CommandLine(new WizCommand());
        command.setErr(new PrintWriter(error));

        assertEquals(1, command.execute(
                "bundle", "--root", workspace.toString(), "--output", workspace.toString()));
        assertTrue(error.toString().contains("must not be the workspace"));
        assertTrue(Files.isRegularFile(source));
    }

    @Test
    void serviceCommandListsDetailsAndAcceptsAliases() throws Exception {
        Path systemd = tempDir.resolve("systemd");
        Path bin = tempDir.resolve("bin");
        Path log = tempDir.resolve("log");
        Files.createDirectories(systemd);
        Files.createDirectories(bin);
        Files.createDirectories(log);
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace.resolve("config"));
        Files.writeString(workspace.resolve("config/application.yml"), "server:\n  port: 19191\nwiz:\n  runtime:\n    devmode-cookie-name: season-wiz-devmode\n");
        Files.writeString(workspace.resolve("config/wiz.yml"), "workspace: java\n");
        Files.writeString(systemd.resolve("wiz.demo.service"), "[Unit]\nDescription=wiz.demo\n");
        Files.writeString(bin.resolve("wiz.demo"), "#!/bin/sh\n"
                + "# wiz.service.name=demo\n"
                + "# wiz.service.root=" + workspace + "\n"
                + "# wiz.service.port=config\n"
                + "# wiz.service.bundle=true\n"
                + "# wiz.service.command=wiz-spring\n"
                + "# wiz.service.log=" + log.resolve("demo") + "\n");

        CommandLine command = new CommandLine(new WizCommand());
        StringWriter output = new StringWriter();
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("service"));
        assertTrue(output.toString().contains("Usage:"));

        output.getBuffer().setLength(0);
        assertEquals(0, command.execute("service", "list", "--systemd-dir", systemd.toString(), "--bin-dir", bin.toString(), "--log-dir", log.toString()));
        String list = output.toString();
        assertTrue(list.contains("name"));
        assertTrue(list.contains("systemd"));
        assertTrue(list.contains("binary"));
        assertTrue(list.contains("| demo "));
        assertTrue(list.contains("demo"));
        assertTrue(list.contains("19191"));
        assertFalse(list.contains(" config "));
        assertFalse(list.contains("bundle"));
        assertFalse(list.contains("command"));
        assertFalse(list.contains("wiz-spring"));

        Path runtimeJar = tempDir.resolve("wiz-runtime.jar");
        writeFakeRuntimeJar(runtimeJar);
        output.getBuffer().setLength(0);
        Path serviceRoot = tempDir.resolve("workspace/src/app/page.dashboard");
        Files.createDirectories(serviceRoot);
        Path externalRuntime = Files.createDirectories(tempDir.resolve("external/runtime"));
        Path externalCache = Files.createDirectories(tempDir.resolve("external/cache"));
        Path externalState = Files.createDirectories(tempDir.resolve("external/state"));
        assertEquals(0, command.execute(
                "service", "install", "demo", "bundle",
                "--root", serviceRoot.toString(),
                "--jar", runtimeJar.toString(),
                "--runtime-dir", externalRuntime.toString(),
                "--cache-dir", externalCache.toString(),
                "--state-dir", externalState.toString(),
                "--dry-run",
                "--systemd-dir", systemd.toString(),
                "--bin-dir", bin.toString()));
        String dryRun = output.toString();
        assertTrue(dryRun.contains("#!/bin/bash"));
        assertTrue(dryRun.contains("export PS1=${PS1:-wiz-service}"));
        assertTrue(dryRun.contains("shopt -s expand_aliases"));
        assertTrue(dryRun.contains("[ -r \"${HOME}/.bashrc\" ]"));
        assertTrue(dryRun.contains("cd '" + tempDir.resolve("workspace") + "'"));
        assertTrue(dryRun.contains("type 'wiz-spring' >/dev/null 2>&1"));
        assertTrue(dryRun.contains("exec wiz-spring run"));
        assertTrue(dryRun.contains("# wiz.service.port=config"));
        assertTrue(dryRun.contains("# wiz.service.command=wiz-spring"));
        assertTrue(dryRun.contains("--bundle"));
        assertTrue(dryRun.contains("# wiz.service.log=/var/log/wiz.demo/application.log"));
        assertTrue(dryRun.contains("--log '/var/log/wiz.demo/application.log'"));
        assertFalse(dryRun.contains("java -jar"));
        assertFalse(dryRun.contains(runtimeJar.toString()));
        assertFalse(dryRun.contains(" run --root "));
        assertFalse(dryRun.contains("--host 0.0.0.0"));
        assertFalse(dryRun.contains("--port 3000"));
        assertTrue(dryRun.contains("RestartSec=5s"));
        assertTrue(dryRun.contains("TimeoutStopSec=30s"));
        assertTrue(dryRun.contains("SuccessExitStatus=143"));
        assertTrue(dryRun.contains("User=" + Files.getOwner(tempDir.resolve("workspace")).getName()));
        assertTrue(dryRun.contains("LogsDirectory=wiz.demo"));
        assertFalse(dryRun.contains("LogsDirectory=wiz/"));
        assertTrue(dryRun.contains("Environment=\"WIZ_SPRING_RUNTIME_DIR="
                + externalRuntime + "\""));
        assertTrue(dryRun.contains("Environment=\"WIZ_SPRING_CACHE_DIR="
                + externalCache + "\""));
        assertTrue(dryRun.contains("Environment=\"WIZ_SPRING_STATE_DIR="
                + externalState + "\""));

        output.getBuffer().setLength(0);
        assertEquals(0, command.execute("service", "rm", "demo", "--dry-run", "--systemd-dir", systemd.toString(), "--bin-dir", bin.toString()));
        assertTrue(output.toString().contains(bin.resolve("wiz.demo").toString()));
    }

    @Test
    void serviceRegistrationRejectsLineBreakingMetadataAndPathInputs() throws Exception {
        Path systemd = tempDir.resolve("safe-systemd");
        Path bin = tempDir.resolve("safe-bin");
        Path logDir = tempDir.resolve("safe-log");
        Files.createDirectories(systemd);
        Files.createDirectories(bin);
        Files.createDirectories(logDir);
        Path workspace = minimalWorkspace("safe-service-workspace");
        Path lineBreakingWorkspace = minimalWorkspace("service-workspace\nprintf-injected");

        assertServiceRegistrationRejected(
                "Workspace root must be a single line",
                "demo",
                "--root", lineBreakingWorkspace.toString(),
                "--dry-run",
                "--systemd-dir", systemd.toString(),
                "--bin-dir", bin.toString(),
                "--log-dir", logDir.toString());
        assertServiceRegistrationRejected(
                "Log path must be a single line",
                "demo",
                "--root", workspace.toString(),
                "--log", tempDir.resolve("server.log\nprintf-injected").toString(),
                "--dry-run",
                "--systemd-dir", systemd.toString(),
                "--bin-dir", bin.toString());
        assertServiceRegistrationRejected(
                "Service name must be a single line",
                "demo\nprintf-injected",
                "--root", workspace.toString(),
                "--dry-run",
                "--systemd-dir", systemd.toString(),
                "--bin-dir", bin.toString());
        assertServiceRegistrationRejected(
                "Invalid service name",
                ".",
                "--root", workspace.toString(),
                "--dry-run",
                "--systemd-dir", systemd.toString(),
                "--bin-dir", bin.toString());
        assertServiceRegistrationRejected(
                "Invalid service name",
                "..",
                "--root", workspace.toString(),
                "--dry-run",
                "--systemd-dir", systemd.toString(),
                "--bin-dir", bin.toString());
        assertServiceRegistrationRejected(
                "Service command must be a single line",
                "demo",
                "--root", workspace.toString(),
                "--command", "wiz-spring\nprintf-injected",
                "--dry-run",
                "--systemd-dir", systemd.toString(),
                "--bin-dir", bin.toString());
        assertServiceRegistrationRejected(
                "Service user must be a single line",
                "demo",
                "--root", workspace.toString(),
                "--user", "root\nprintf-injected",
                "--dry-run",
                "--systemd-dir", systemd.toString(),
                "--bin-dir", bin.toString());
        assertServiceRegistrationRejected(
                "Service definition path must be a single line",
                "demo",
                "--root", workspace.toString(),
                "--dry-run",
                "--systemd-dir", tempDir.resolve("systemd\nprintf-injected").toString(),
                "--bin-dir", bin.toString());
        assertServiceRegistrationRejected(
                "Service executable path must be an absolute path without whitespace",
                "demo",
                "--root", workspace.toString(),
                "--dry-run",
                "--systemd-dir", systemd.toString(),
                "--bin-dir", tempDir.resolve("bin path;systemd-injected").toString());
        assertServiceRegistrationRejected(
                "must be outside the workspace",
                "demo",
                "--root", workspace.toString(),
                "--runtime-dir", workspace.resolve("runtime").toString(),
                "--dry-run",
                "--systemd-dir", systemd.toString(),
                "--bin-dir", bin.toString());
        assertServiceRegistrationRejected(
                "Custom log parent must already exist",
                "demo",
                "--root", workspace.toString(),
                "--log", tempDir.resolve("missing-log-parent/server.log").toString(),
                "--dry-run",
                "--systemd-dir", systemd.toString(),
                "--bin-dir", bin.toString());
    }

    @Test
    void serviceRegistrationRejectsMissingAndSymlinkExternalRuntimeDirectories() throws Exception {
        Path systemd = Files.createDirectories(tempDir.resolve("external-validation-systemd"));
        Path bin = Files.createDirectories(tempDir.resolve("external-validation-bin"));
        Path workspace = minimalWorkspace("external-validation-workspace");
        Path missingRuntime = tempDir.resolve("missing-external-runtime");

        assertServiceRegistrationRejected(
                "WIZ_SPRING_RUNTIME_DIR must already exist as a real directory owned by the service user",
                "demo",
                "--root", workspace.toString(),
                "--runtime-dir", missingRuntime.toString(),
                "--dry-run",
                "--systemd-dir", systemd.toString(),
                "--bin-dir", bin.toString());

        Path runtimeTarget = Files.createDirectories(tempDir.resolve("external-runtime-target"));
        Path runtimeLink = tempDir.resolve("external-runtime-link");
        Files.createSymbolicLink(runtimeLink, runtimeTarget);
        assertServiceRegistrationRejected(
                "WIZ_SPRING_RUNTIME_DIR must already exist as a real directory owned by the service user",
                "demo",
                "--root", workspace.toString(),
                "--runtime-dir", runtimeLink.toString(),
                "--dry-run",
                "--systemd-dir", systemd.toString(),
                "--bin-dir", bin.toString());
    }

    @Test
    void serviceRegistrationRejectsExternalRuntimeDirectoryWithoutOwnerPermissions() throws Exception {
        Path runtimeDir = Files.createDirectories(tempDir.resolve("external-runtime-without-owner-permissions"));
        if (!Files.getFileStore(runtimeDir).supportsFileAttributeView("posix")) {
            return;
        }
        Path systemd = Files.createDirectories(tempDir.resolve("permission-validation-systemd"));
        Path bin = Files.createDirectories(tempDir.resolve("permission-validation-bin"));
        Path workspace = minimalWorkspace("permission-validation-workspace");

        Files.setPosixFilePermissions(runtimeDir, Set.of());
        try {
            assertServiceRegistrationRejected(
                    "WIZ_SPRING_RUNTIME_DIR directory must be owner-writable, readable and searchable",
                    "demo",
                    "--root", workspace.toString(),
                    "--runtime-dir", runtimeDir.toString(),
                    "--dry-run",
                    "--systemd-dir", systemd.toString(),
                    "--bin-dir", bin.toString());
        } finally {
            Files.setPosixFilePermissions(runtimeDir, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test
    void serviceUninstallStopsDisablesDeletesAndReloads() throws Exception {
        Path systemd = tempDir.resolve("uninstall-systemd");
        Path bin = tempDir.resolve("uninstall-bin");
        Files.createDirectories(systemd);
        Files.createDirectories(bin);
        Path commandPath = bin.resolve("wiz.demo");
        Path servicePath = systemd.resolve("wiz.demo.service");
        Files.writeString(commandPath, "#!/bin/sh\n");
        Files.writeString(servicePath, "[Unit]\nDescription=wiz.demo\n");

        Path calls = tempDir.resolve("systemctl.calls");
        Path systemctl = tempDir.resolve("systemctl");
        Files.writeString(systemctl, "#!/bin/sh\nprintf '%s\\n' \"$*\" >> '" + calls + "'\n");
        systemctl.toFile().setExecutable(true, false);

        CommandLine command = new CommandLine(new WizCommand());
        assertEquals(0, command.execute(
                "service", "uninstall", "demo",
                "--systemd-dir", systemd.toString(),
                "--bin-dir", bin.toString(),
                "--systemctl", systemctl.toString()));

        assertFalse(Files.exists(commandPath));
        assertFalse(Files.exists(servicePath));
        assertEquals(String.join(System.lineSeparator(),
                "stop wiz.demo",
                "disable wiz.demo",
                "daemon-reload") + System.lineSeparator(), Files.readString(calls));
    }

    @Test
    void serviceLogsShowsApplicationLogAndInvokesJournalctl() throws Exception {
        Path bin = tempDir.resolve("logs-bin");
        Path logDir = tempDir.resolve("logs-dir");
        Files.createDirectories(bin);
        Files.createDirectories(logDir);
        Path applicationLog = logDir.resolve("custom-demo.log");
        Files.writeString(bin.resolve("wiz.demo"), "#!/bin/sh\n# wiz.service.log=" + applicationLog + "\n");

        Path calls = tempDir.resolve("journalctl.calls");
        Path journalctl = tempDir.resolve("journalctl");
        Files.writeString(journalctl, "#!/bin/sh\nprintf '%s\\n' \"$*\" > '" + calls + "'\n");
        journalctl.toFile().setExecutable(true, false);
        StringWriter output = new StringWriter();
        CommandLine command = new CommandLine(new WizCommand());
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute(
                "service", "logs", "demo", "--lines", "25", "--follow",
                "--journalctl", journalctl.toString(),
                "--bin-dir", bin.toString(),
                "--log-dir", logDir.toString()));

        assertTrue(output.toString().contains("Service: wiz.demo"));
        assertTrue(output.toString().contains("Application log: " + applicationLog));
        assertEquals("--unit wiz.demo --lines 25 --no-pager --follow" + System.lineSeparator(), Files.readString(calls));
    }

    @Test
    void workspaceCommandsRejectNonWorkspaceRoots() {
        Path outside = tempDir.resolve("outside");
        StringWriter error = new StringWriter();

        CommandLine run = new CommandLine(new WizCommand());
        run.setErr(new PrintWriter(error));
        assertEquals(1, run.execute("run", "--dry-run", "--root", outside.toString()));
        assertTrue(error.toString().contains("WIZ Spring workspace root not found"));

        error.getBuffer().setLength(0);
        CommandLine mcp = new CommandLine(new WizCommand());
        mcp.setErr(new PrintWriter(error));
        assertEquals(1, mcp.execute("mcp", "--root", outside.toString()));
        assertTrue(error.toString().contains("WIZ Spring workspace root not found"));

        error.getBuffer().setLength(0);
        CommandLine service = new CommandLine(new WizCommand());
        service.setErr(new PrintWriter(error));
        assertEquals(1, service.execute("service", "install", "demo", "--root", outside.toString(), "--dry-run"));
        assertTrue(error.toString().contains("WIZ Spring workspace root not found"));
        assertTrue(error.toString().contains("config/wiz.yml"));
        assertTrue(error.toString().contains("wiz-spring create"));
    }

    @Test
    void workspaceCommandsRejectGenericSpringAndMalformedWizLayouts() throws Exception {
        Path generic = tempDir.resolve("generic-spring");
        Files.createDirectories(generic.resolve("config"));
        Files.createDirectories(generic.resolve("src/main/java"));
        Files.writeString(generic.resolve("config/application.yml"), "server:\n  port: 18080\n");
        StringWriter error = new StringWriter();
        CommandLine genericRun = new CommandLine(new WizCommand());
        genericRun.setErr(new PrintWriter(error));

        assertEquals(1, genericRun.execute("run", "--dry-run", "--root", generic.toString()));
        assertTrue(error.toString().contains("config/wiz.yml"));

        Path wrongRuntime = tempDir.resolve("python-marker");
        Files.createDirectories(wrongRuntime.resolve("config"));
        Files.createDirectories(wrongRuntime.resolve("src/app"));
        Files.writeString(wrongRuntime.resolve("config/application.yml"), "server:\n  port: 18081\n");
        Files.writeString(wrongRuntime.resolve("config/wiz.yml"), "workspace: python\n");
        error.getBuffer().setLength(0);
        CommandLine wrongRuntimeRun = new CommandLine(new WizCommand());
        wrongRuntimeRun.setErr(new PrintWriter(error));

        assertEquals(1, wrongRuntimeRun.execute("run", "--dry-run", "--root", wrongRuntime.toString()));
        assertTrue(error.toString().contains("workspace must be 'java'"));

        Path missingSources = tempDir.resolve("missing-wiz-sources");
        Files.createDirectories(missingSources.resolve("config"));
        Files.writeString(missingSources.resolve("config/application.yml"), "server:\n  port: 18082\n");
        Files.writeString(missingSources.resolve("config/wiz.yml"), "workspace: java\n");
        error.getBuffer().setLength(0);
        CommandLine missingSourcesBuild = new CommandLine(new WizCommand());
        missingSourcesBuild.setErr(new PrintWriter(error));

        assertEquals(1, missingSourcesBuild.execute(
                "build", "--phase", "reconstruct", "--root", missingSources.toString()));
        assertTrue(error.toString().contains("expected src/app or bundle/src/app"));
    }

    @Test
    void buildUsesExecutableWorkspaceMavenWrapper() throws Exception {
        Path workspace = minimalWorkspace("maven-wrapper-workspace");
        Files.writeString(workspace.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>wrapper-test</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        Path calls = tempDir.resolve("mvnw.calls");
        Path wrapper = workspace.resolve("mvnw");
        Files.writeString(wrapper, "#!/bin/sh\nprintf '%s\\n' \"$*\" >> '" + calls + "'\n");
        wrapper.toFile().setExecutable(true, false);

        assertEquals(0, new CommandLine(new WizCommand()).execute(
                "build", "--root", workspace.toString(), "--phase", "compile"));
        assertEquals(0, new CommandLine(new WizCommand()).execute(
                "build", "--root", workspace.toString(), "--phase", "compile"));
        String invoked = Files.readString(calls);
        assertTrue(invoked.contains("--batch-mode"));
        assertTrue(invoked.contains("dependency:copy-dependencies"));
        assertEquals(1, Files.readAllLines(calls).size(), "unchanged build must reuse verified Maven dependencies");

        assertEquals(0, new CommandLine(new WizCommand()).execute(
                "build", "--root", workspace.toString(), "--phase", "compile", "--clean"));
        assertEquals(2, Files.readAllLines(calls).size(), "clean build must resolve Maven dependencies again");
    }

    @Test
    void buildReportsBrokenMavenWrapperBeforeChangingWorkspace() throws Exception {
        Path workspace = minimalWorkspace("broken-maven-wrapper-workspace");
        Files.writeString(workspace.resolve("pom.xml"), "<project/>\n");
        Path wrapper = workspace.resolve("mvnw");
        Files.writeString(wrapper, "#!/bin/sh\nexit 0\n");
        wrapper.toFile().setExecutable(false, false);
        StringWriter error = new StringWriter();
        CommandLine command = new CommandLine(new WizCommand());
        command.setErr(new PrintWriter(error));

        assertEquals(1, command.execute(
                "build", "--root", workspace.toString(), "--phase", "compile"));
        assertTrue(error.toString().contains("Maven Wrapper is not executable"));
        assertTrue(error.toString().contains("chmod +x mvnw"));
        assertFalse(Files.exists(workspace.resolve("build")), "prerequisite checks must run before build output is changed");
    }

    @Test
    void invalidBuildPhaseDoesNotApplyRequestedPackageChange() throws Exception {
        Path workspace = minimalWorkspace("invalid-phase-package-workspace");
        Path source = workspace.resolve("src/app/page.dashboard/api.java");
        Path pom = workspace.resolve("pom.xml");
        Files.writeString(source, "package com.wiz.app.web.api;\npublic final class PageDashboardApi {}\n");
        Files.writeString(pom, "<project><groupId>com.wiz.app</groupId></project>\n");
        String applicationBefore = Files.readString(workspace.resolve("config/application.yml"));
        String sourceBefore = Files.readString(source);
        String pomBefore = Files.readString(pom);

        int exitCode = new CommandLine(new WizCommand()).execute(
                "build", "--root", workspace.toString(),
                "--phase", "invalid", "--package", "com.example.changed");

        assertEquals(2, exitCode);
        assertEquals(applicationBefore, Files.readString(workspace.resolve("config/application.yml")));
        assertEquals(sourceBefore, Files.readString(source));
        assertEquals(pomBefore, Files.readString(pom));
        assertTrue(Files.notExists(workspace.resolve("build")));
    }

    @Test
    void runAcceptsDeployBundleWorkspaceLayout() throws Exception {
        Path workspace = tempDir.resolve("deploy-bundle-workspace");
        Files.createDirectories(workspace.resolve("config"));
        Files.createDirectories(workspace.resolve("bundle/src/app"));
        Files.writeString(workspace.resolve("config/application.yml"), "server:\n  port: 18083\n");
        Files.writeString(workspace.resolve("config/wiz.yml"), "workspace: java\n");
        StringWriter output = new StringWriter();
        CommandLine command = new CommandLine(new WizCommand());
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("run", "--dry-run", "--root", workspace.toString(), "--bundle"));
        assertTrue(output.toString().contains("root=" + workspace.toAbsolutePath().normalize()));
        assertTrue(output.toString().contains("bundle=true"));

        assertEquals(2, new CommandLine(new WizCommand()).execute(
                "build", "--clean", "--phase", "reconstruct", "--root", workspace.toString()));
        assertTrue(Files.isDirectory(workspace.resolve("bundle/src/app")));
        assertTrue(Files.notExists(workspace.resolve("build")));
    }

    @Test
    void workspaceCommandShellAcceptsExpectedOptions() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path runtimeJar = tempDir.resolve("wiz-runtime.jar");
        writeFakeRuntimeJar(runtimeJar);
        assertEquals(0, new CommandLine(new WizCommand()).execute("create", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("build", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("jar", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("bundle", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("kill", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("service", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("service", "logs", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("mcp", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("completion", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute(
                "create", workspace.toString(),
                "--package", "com.wiz.bootstrap",
                "--runtime-jar", runtimeJar.toString(),
                "--skip-build"));
        assertTrue(Files.exists(workspace.resolve("src/app/page.dashboard/api.java")));
        assertTrue(Files.exists(workspace.resolve(".codex/config.toml")));
        assertTrue(Files.exists(workspace.resolve(".github/copilot-instructions.md")));
        assertFalse(Files.exists(workspace.resolve(".wiz")));
        deleteIfExists(workspace.resolve("src/angular"));
        assertEquals(0, new CommandLine(new WizCommand()).execute(
                "build", "--root", workspace.toString(), "--package", "com.example.initial"));
        assertTrue(Files.exists(workspace.resolve("build/src/main/java/com/example/initial/web/api/PageDashboardApi.java")));
        assertFalse(Files.exists(workspace.resolve("build/src/main/java/com/wiz/bootstrap/web/api/PageDashboardApi.java")));
        assertTrue(Files.readString(workspace.resolve("config/application.yml"))
                .contains("package-root: com.example.initial"));
        assertTrue(Files.notExists(workspace.resolve("build/src/app/page.dashboard/api.java")));
        assertTrue(Files.exists(workspace.resolve("build/target/work/source/app/page.dashboard/api.java")));
        assertTrue(Files.exists(workspace.resolve("bundle/app-api.jar")));
        CommandLine latePackageChange = new CommandLine(new WizCommand());
        assertEquals(0, latePackageChange.execute(
                "build", "--root", workspace.toString(), "--package", "com.example.too.late"));
        assertTrue(Files.exists(workspace.resolve("build/src/main/java/com/example/too/late/web/api/PageDashboardApi.java")));
        assertFalse(Files.exists(workspace.resolve("build/src/main/java/com/example/initial/web/api/PageDashboardApi.java")));
        assertTrue(Files.readString(workspace.resolve("config/application.yml"))
                .contains("package-root: com.example.too.late"));
        assertTrue(Files.readString(workspace.resolve("bundle").resolve(BuildMarkerService.MARKER_FILE))
                .contains("\"javaPackageRoot\" : \"com.example.too.late\""));
        Path appJar = tempDir.resolve("main.jar");
        assertEquals(0, new CommandLine(new WizCommand()).execute("jar", "--root", workspace.toString(), "--skip-build", "--runtime-jar", runtimeJar.toString(), "--output", appJar.toString()));
        assertTrue(Files.exists(appJar));
        Path bundle = workspace.resolve("deploy-bundle");
        assertEquals(0, new CommandLine(new WizCommand()).execute("bundle", "--root", workspace.toString(), "--output", bundle.toString()));
        assertTrue(Files.exists(bundle.resolve("bundle/app-api.jar")));
        assertTrue(Files.exists(bundle.resolve("bundle/bom.json")));
        assertEquals(0, new CommandLine(new WizCommand()).execute("kill", "--dry-run"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("service", "regist", "demo", "19090", "bundle", "--root", workspace.toString(), "--jar", tempDir.resolve("wiz-spring.jar").toString(), "--dry-run"));
    }

    @Test
    void createAlwaysSetsUpCodexForDefaultAndImportedWorkspaces() throws Exception {
        Path runtimeJar = tempDir.resolve("wiz-spring.jar");
        writeFakeRuntimeJar(runtimeJar);

        Path workspace = tempDir.resolve("default-codex-workspace");
        assertEquals(0, new CommandLine(new WizCommand()).execute(
                "create", workspace.toString(),
                "--package", "com.wiz.app",
                "--runtime-jar", runtimeJar.toString(),
                "--skip-build"));
        assertCodexSetup(workspace, runtimeJar);

        Path source = tempDir.resolve("import-source");
        Files.createDirectories(source.resolve("src/app/page.imported"));
        Files.writeString(source.resolve("src/app/page.imported/app.json"), "{}\n");
        Files.createDirectories(source.resolve(".codex"));
        Files.writeString(source.resolve(".codex/config.toml"), "legacy = true\n");
        Files.createDirectories(source.resolve(".github/custom"));
        Files.writeString(source.resolve(".github/copilot-instructions.md"), "legacy instructions\n");
        Files.writeString(source.resolve(".github/custom/custom-instructions.md"), "keep this custom file\n");

        Path importedWorkspace = tempDir.resolve("imported-codex-workspace");
        assertEquals(0, new CommandLine(new WizCommand()).execute(
                "create", importedWorkspace.toString(),
                "--package", "com.wiz.imported",
                "--path", source.toString(),
                "--runtime-jar", runtimeJar.toString(),
                "--skip-build"));
        assertCodexSetup(importedWorkspace, runtimeJar);
        assertTrue(Files.exists(importedWorkspace.resolve("src/app/page.imported/app.json")));
        assertTrue(Files.readString(importedWorkspace.resolve(".github/copilot-instructions.md"))
                .contains("# WIZ Spring Copilot Instructions"));
        assertEquals("keep this custom file\n",
                Files.readString(importedWorkspace.resolve(".github/custom/custom-instructions.md")));
    }

    private void assertCodexSetup(Path workspace, Path runtimeJar) throws Exception {
        String configText = Files.readString(workspace.resolve(".codex/config.toml"));
        assertTrue(configText.contains("[mcp_servers.\"wiz-spring\"]"));
        assertTrue(configText.contains("[mcp_servers.\"wiz-spring\".env]"));
        assertTrue(configText.contains(runtimeJar.toAbsolutePath().normalize().toString()));
        assertTrue(configText.contains(workspace.toAbsolutePath().normalize().toString()));
        Path mcpState = WorkspaceRuntimePaths.mcpState(workspace);
        assertTrue(configText.contains("\"--state\""));
        assertTrue(configText.contains(mcpState.toString()));
        String writableRoots = configText.lines()
                .filter(line -> line.stripLeading().startsWith("writable_roots ="))
                .findFirst()
                .orElseThrow();
        assertTrue(writableRoots.contains(mcpState.getParent().toString()));
        assertFalse(configText.contains(".wiz/mcp-state.json"));
        assertFalse(Files.exists(workspace.resolve(".wiz")));
        assertTrue(Files.readString(workspace.resolve(".codex/AGENTS.md")).contains("WIZ Spring work"));
        assertTrue(Files.readString(workspace.resolve(".github/copilot-instructions.md"))
                .contains("# WIZ Spring Copilot Instructions"));
        assertTrue(Files.readString(workspace.resolve(".github/devdocs/instructions/api-quick-reference.md"))
                .contains("# API Quick Reference"));
        assertTrue(Files.readString(workspace.resolve(".github/prompts/development-rules.prompt.md"))
                .contains("WIZ Spring"));
        assertTrue(Files.isRegularFile(workspace.resolve(".github/short-instructions.md")));
    }

    private void assertServiceRegistrationRejected(String expectedError, String name, String... options) {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine command = new CommandLine(new WizCommand());
        command.setOut(new PrintWriter(output));
        command.setErr(new PrintWriter(error));
        java.util.ArrayList<String> args = new java.util.ArrayList<>();
        args.add("service");
        args.add("install");
        args.add(name);
        args.addAll(java.util.List.of(options));

        assertEquals(1, command.execute(args.toArray(String[]::new)));
        assertTrue(error.toString().contains(expectedError), error.toString());
        assertFalse(output.toString().contains("# wiz.service."));
    }

    private void deleteIfExists(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    private void writeFakeRuntimeJar(Path jar) throws Exception {
        try (java.util.jar.JarOutputStream output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new java.util.jar.JarEntry("META-INF/MANIFEST.MF"));
            output.write("Manifest-Version: 1.0\nMain-Class: com.wiz.WizSpringApplication\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private Path minimalWorkspace(String name) throws Exception {
        Path workspace = tempDir.resolve(name);
        Files.createDirectories(workspace.resolve("config"));
        Files.createDirectories(workspace.resolve("src/app/page.dashboard"));
        Files.writeString(workspace.resolve("config/application.yml"), "server:\n  port: 19191\nwiz:\n  java:\n    package-root: com.wiz.app\n");
        Files.writeString(workspace.resolve("config/wiz.yml"), "workspace: java\n");
        return workspace.toAbsolutePath().normalize();
    }
}
