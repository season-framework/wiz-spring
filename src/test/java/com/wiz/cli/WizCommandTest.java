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

class WizCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void exposesHelpAndVersion() {
        StringWriter output = new StringWriter();
        CommandLine command = new CommandLine(new WizCommand());
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("--version"));
        assertTrue(output.toString().contains("wiz-spring 0.2.1"));

        output.getBuffer().setLength(0);
        assertEquals(0, command.execute("--help"));
        String help = output.toString();
        assertTrue(help.contains("Usage: wiz-spring"));
        assertTrue(help.contains("Codex setup CLI"));
        assertTrue(help.contains("codex"));
        assertFalse(help.contains("wiz-java"));
    }

    @Test
    void helpMentionsSpringMcpAndCodexServerName() {
        CommandLine command = new CommandLine(new WizCommand());
        StringWriter output = new StringWriter();
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("mcp", "--help"));
        String mcpHelp = output.toString();
        assertTrue(mcpHelp.contains("standalone WIZ Spring MCP server"));
        assertTrue(mcpHelp.contains("server name"));
        assertTrue(mcpHelp.contains("'wiz-spring'"));
        assertTrue(mcpHelp.contains("<workspace>/.wiz/mcp-state.json"));

        output.getBuffer().setLength(0);
        assertEquals(0, command.execute("codex", "--help"));
        String codexHelp = output.toString();
        assertTrue(codexHelp.contains("Writes server name"));
        assertTrue(codexHelp.contains("'wiz-spring'"));
        assertTrue(codexHelp.contains("exit code 2"));
        assertTrue(codexHelp.contains("generated MCP"));
        assertTrue(codexHelp.contains("args"));
    }

    @Test
    void runDryRunDoesNotStartServer() throws Exception {
        Path workspace = minimalWorkspace("run-workspace");
        Path child = workspace.resolve("src/app/page.dashboard");
        StringWriter output = new StringWriter();
        CommandLine command = new CommandLine(new WizCommand());
        command.setOut(new PrintWriter(output));

        int exitCode = command.execute("run", "--dry-run", "--root", child.toString(), "--port", "18080", "--bundle", "--profile", "prod", "--log", tempDir.resolve("server.log").toString());

        assertEquals(0, exitCode);
        assertTrue(output.toString().contains("root=" + workspace));
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
        assertEquals(0, command.execute(
                "service", "install", "demo", "bundle",
                "--root", serviceRoot.toString(),
                "--jar", runtimeJar.toString(),
                "--dry-run",
                "--systemd-dir", systemd.toString(),
                "--bin-dir", bin.toString(),
                "--log-dir", log.toString()));
        String dryRun = output.toString();
        assertTrue(dryRun.contains("#!/bin/bash"));
        assertTrue(dryRun.contains("export PS1=${PS1:-wiz-service}"));
        assertTrue(dryRun.contains("shopt -s expand_aliases"));
        assertTrue(dryRun.contains("source /root/.bashrc"));
        assertTrue(dryRun.contains("cd '" + tempDir.resolve("workspace") + "'"));
        assertTrue(dryRun.contains("type 'wiz-spring' >/dev/null 2>&1"));
        assertTrue(dryRun.contains("wiz-spring run"));
        assertTrue(dryRun.contains("# wiz.service.port=config"));
        assertTrue(dryRun.contains("# wiz.service.command=wiz-spring"));
        assertTrue(dryRun.contains("--bundle"));
        assertTrue(dryRun.contains("--log '" + log.resolve("demo") + "'"));
        assertFalse(dryRun.contains("java -jar"));
        assertFalse(dryRun.contains(runtimeJar.toString()));
        assertFalse(dryRun.contains(" run --root "));
        assertFalse(dryRun.contains("--host 0.0.0.0"));
        assertFalse(dryRun.contains("--port 3000"));

        output.getBuffer().setLength(0);
        assertEquals(0, command.execute("service", "rm", "demo", "--dry-run", "--systemd-dir", systemd.toString(), "--bin-dir", bin.toString()));
        assertTrue(output.toString().contains(bin.resolve("wiz.demo").toString()));
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
    }

    @Test
    void workspaceCommandShellAcceptsExpectedOptions() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        assertEquals(0, new CommandLine(new WizCommand()).execute("create", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("jar", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("bundle", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("kill", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("service", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("mcp", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("codex", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("create", workspace.toString(), "--package", "com.wiz.app", "--skip-build"));
        assertTrue(Files.exists(workspace.resolve("src/app/page.dashboard/api.java")));
        deleteIfExists(workspace.resolve("src/angular"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("build", "--root", workspace.toString(), "--clean"));
        assertTrue(Files.exists(workspace.resolve("build/src/main/java/com/wiz/app/web/api/PageDashboardApi.java")));
        assertTrue(Files.notExists(workspace.resolve("build/src/app/page.dashboard/api.java")));
        assertTrue(Files.exists(workspace.resolve("build/.wiz/source/app/page.dashboard/api.java")));
        assertTrue(Files.exists(workspace.resolve("bundle/app-api.jar")));
        Path runtimeJar = tempDir.resolve("wiz-runtime.jar");
        writeFakeRuntimeJar(runtimeJar);
        Path appJar = tempDir.resolve("main.jar");
        assertEquals(0, new CommandLine(new WizCommand()).execute("jar", "--root", workspace.toString(), "--skip-build", "--runtime-jar", runtimeJar.toString(), "--output", appJar.toString()));
        assertTrue(Files.exists(appJar));
        Path bundle = workspace.resolve("deploy-bundle");
        assertEquals(0, new CommandLine(new WizCommand()).execute("bundle", "--root", workspace.toString(), "--output", bundle.toString()));
        assertTrue(Files.exists(bundle.resolve("bundle/app-api.jar")));
        assertEquals(0, new CommandLine(new WizCommand()).execute("kill", "--dry-run"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("service", "regist", "demo", "19090", "bundle", "--root", workspace.toString(), "--jar", tempDir.resolve("wiz-spring.jar").toString(), "--dry-run"));
    }

    @Test
    void codexCommandCreatesWarnsAndOverwritesSettings() throws Exception {
        Path workspace = tempDir.resolve("codex-workspace");
        Files.createDirectories(workspace.resolve("config"));
        Files.writeString(workspace.resolve("config/application.yml"), "wiz:\n  java:\n    package-root: com.wiz.app\n");
        Files.writeString(workspace.resolve("config/wiz.yml"), "workspace: java\n");
        Path runtimeJar = tempDir.resolve("wiz-spring.jar");
        writeFakeRuntimeJar(runtimeJar);

        assertEquals(0, new CommandLine(new WizCommand()).execute(
                "codex",
                "--root", workspace.toString(),
                "--runtime-jar", runtimeJar.toString()));

        Path config = workspace.resolve(".codex/config.toml");
        Path agents = workspace.resolve(".codex/AGENTS.md");
        String configText = Files.readString(config);
        assertTrue(configText.contains("[mcp_servers.\"wiz-spring\"]"));
        assertTrue(configText.contains("[mcp_servers.\"wiz-spring\".env]"));
        assertTrue(configText.contains(runtimeJar.toAbsolutePath().normalize().toString()));
        assertTrue(Files.readString(agents).contains("WIZ Spring work"));

        Files.writeString(config, "legacy = true\n");
        assertEquals(2, new CommandLine(new WizCommand()).execute(
                "codex",
                "--root", workspace.toString(),
                "--runtime-jar", runtimeJar.toString()));
        assertEquals("legacy = true\n", Files.readString(config));

        assertEquals(0, new CommandLine(new WizCommand()).execute(
                "codex",
                "--root", workspace.toString(),
                "--runtime-jar", runtimeJar.toString(),
                "--force"));
        assertTrue(Files.readString(config).contains("[mcp_servers.\"wiz-spring\"]"));
        assertEquals(0, new CommandLine(new WizCommand()).execute(
                "codex",
                "--root", workspace.toString(),
                "--runtime-jar", runtimeJar.toString(),
                "--check"));
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
