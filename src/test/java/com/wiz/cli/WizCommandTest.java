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
        assertTrue(output.toString().contains("wiz-spring 0.0.5"));

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
    void runDryRunDoesNotStartServer() {
        int exitCode = new CommandLine(new WizCommand()).execute("run", "--dry-run", "--port", "18080", "--bundle", "--profile", "prod", "--log", tempDir.resolve("server.log").toString());

        assertEquals(0, exitCode);
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
        Files.writeString(workspace.resolve("config/application.yml"), "server:\n  port: 19191\nwiz:\n  project:\n    default-name: main\n");
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
        assertEquals(0, command.execute(
                "service", "install", "demo", "bundle",
                "--root", tempDir.resolve("workspace").toString(),
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
    void projectCommandShellAcceptsExpectedOptions() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "create", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "jar", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("bundle", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("kill", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("service", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("mcp", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("codex", "--help"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("create", workspace.toString()));
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "create", "--root", workspace.toString(), "--project", "main"));
        assertTrue(Files.exists(workspace.resolve("project/main/src/app/page.dashboard/api.java")));
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "app", "create", "--root", workspace.toString(), "--project", "main", "--app", "page.cli", "--engine", "pug"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "controller", "create", "--root", workspace.toString(), "--project", "main", "--controller", "cli"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "route", "create", "--root", workspace.toString(), "--project", "main", "--route", "custom", "--path", "/api/v1", "--methods", "GET,POST"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "package", "create", "--root", workspace.toString(), "--project", "main", "--package", "blog"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "app", "list", "--root", workspace.toString(), "--project", "main"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "controller", "list", "--root", workspace.toString(), "--project", "main"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "route", "list", "--root", workspace.toString(), "--project", "main"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "package", "list", "--root", workspace.toString(), "--project", "main"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "npm", "list", "--root", workspace.toString(), "--project", "main"));
        deleteIfExists(workspace.resolve("project/main/src/angular"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "list", "--root", workspace.toString()));
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "build", "--root", workspace.toString(), "--project", "main", "--clean"));
        assertTrue(Files.exists(workspace.resolve("project/main/build/src/app/page.dashboard/api.java")));
        assertTrue(Files.exists(workspace.resolve("project/main/bundle/project-api.jar")));
        Path runtimeJar = tempDir.resolve("wiz-runtime.jar");
        writeFakeRuntimeJar(runtimeJar);
        Path projectJar = tempDir.resolve("main.jar");
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "jar", "--root", workspace.toString(), "--project", "main", "--skip-build", "--runtime-jar", runtimeJar.toString(), "--output", projectJar.toString()));
        assertTrue(Files.exists(projectJar));
        Path bundle = workspace.resolve("deploy-bundle");
        assertEquals(0, new CommandLine(new WizCommand()).execute("bundle", "--root", workspace.toString(), "--project", "main", "--output", bundle.toString()));
        assertTrue(Files.exists(bundle.resolve("project/main/bundle/project-api.jar")));
        assertEquals(0, new CommandLine(new WizCommand()).execute("kill", "--dry-run"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("service", "regist", "demo", "19090", "bundle", "--root", workspace.toString(), "--jar", tempDir.resolve("wiz-spring.jar").toString(), "--dry-run"));
        Path archive = workspace.resolve("main-export");
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "export", "--root", workspace.toString(), "--project", "main", "--output", archive.toString()));
        assertTrue(Files.exists(workspace.resolve("main-export.wizproject")));
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "app", "delete", "--root", workspace.toString(), "--project", "main", "--app", "page.cli"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "controller", "delete", "--root", workspace.toString(), "--project", "main", "--controller", "cli"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "route", "delete", "--root", workspace.toString(), "--project", "main", "--route", "custom"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "package", "delete", "--root", workspace.toString(), "--project", "main", "--package", "blog"));
        assertEquals(0, new CommandLine(new WizCommand()).execute("project", "delete", "--root", workspace.toString(), "--project", "main"));
    }

    @Test
    void codexCommandCreatesWarnsAndOverwritesSettings() throws Exception {
        Path workspace = tempDir.resolve("codex-workspace");
        Files.createDirectories(workspace.resolve("config"));
        Files.createDirectories(workspace.resolve("project/main"));
        Files.writeString(workspace.resolve("config/application.yml"), "wiz:\n  project:\n    default-name: main\n");
        Path runtimeJar = tempDir.resolve("wiz-spring.jar");
        writeFakeRuntimeJar(runtimeJar);

        assertEquals(0, new CommandLine(new WizCommand()).execute(
                "codex",
                "--root", workspace.toString(),
                "--project", "main",
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
                "--project", "main",
                "--runtime-jar", runtimeJar.toString()));
        assertEquals("legacy = true\n", Files.readString(config));

        assertEquals(0, new CommandLine(new WizCommand()).execute(
                "codex",
                "--root", workspace.toString(),
                "--project", "main",
                "--runtime-jar", runtimeJar.toString(),
                "--force"));
        assertTrue(Files.readString(config).contains("[mcp_servers.\"wiz-spring\"]"));
        assertEquals(0, new CommandLine(new WizCommand()).execute(
                "codex",
                "--root", workspace.toString(),
                "--project", "main",
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
}
