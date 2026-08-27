package com.wiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class WizCommandTest {

    @Test
    void exposesOnlyTheOneDotZeroRootCommands() {
        CommandLine command = new CommandLine(new WizCommand());

        assertEquals(
                Set.of("create", "templates", "service", "completion"),
                command.getSubcommands().keySet());

        StringWriter output = new StringWriter();
        command.setOut(new PrintWriter(output));
        assertEquals(0, command.execute("--help"));

        String help = output.toString();
        assertTrue(help.contains("Usage: wiz-spring"));
        assertTrue(help.contains("create"));
        assertTrue(help.contains("templates"));
        assertTrue(help.contains("service"));
        assertTrue(help.contains("completion"));
        assertFalse(help.contains(System.lineSeparator() + "  build"));
        assertFalse(help.contains(System.lineSeparator() + "  run"));
        assertFalse(help.contains(System.lineSeparator() + "  jar"));
        assertFalse(help.contains(System.lineSeparator() + "  bundle"));
        assertFalse(help.contains(System.lineSeparator() + "  kill"));
        assertFalse(help.contains(System.lineSeparator() + "  mcp"));
    }

    @Test
    void rejectsRemovedZeroDotXCommands() {
        for (String removed : Set.of("build", "run", "jar", "bundle", "kill", "mcp")) {
            StringWriter error = new StringWriter();
            CommandLine command = new CommandLine(new WizCommand());
            command.setErr(new PrintWriter(error));

            assertEquals(2, command.execute(removed), removed);
            assertTrue(error.toString().contains("Unmatched argument"), removed);
        }
    }

    @Test
    void createHelpHasNoCompatibilityBuildOption() {
        StringWriter output = new StringWriter();
        CommandLine command = new CommandLine(new WizCommand());
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("create", "--help"));

        String help = output.toString();
        assertTrue(help.contains("Create a standalone Spring project"));
        assertTrue(help.contains("--template"));
        assertTrue(help.contains("Default: angular-wiz"));
        assertFalse(help.contains("--skip-build"));
        assertFalse(help.contains("--runtime-jar"));
        assertFalse(help.contains("--package-root"));
    }

    @Test
    void serviceInstallAndUninstallUseOnlyCanonicalNames() {
        CommandLine root = new CommandLine(new WizCommand());
        CommandLine service = root.getSubcommands().get("service");

        assertTrue(service.getSubcommands().containsKey("install"));
        assertTrue(service.getSubcommands().containsKey("uninstall"));
        for (String retiredAlias : Set.of(
                "regist", "register", "unregist", "remove", "delete", "rm", "unregister", "ls", "log")) {
            assertFalse(service.getSubcommands().containsKey(retiredAlias), retiredAlias);
        }

        StringWriter output = new StringWriter();
        root.setOut(new PrintWriter(output));
        assertEquals(0, root.execute("service", "--help"));
        assertTrue(output.toString().contains("install"));
        assertTrue(output.toString().contains("uninstall"));

        output.getBuffer().setLength(0);
        assertEquals(0, root.execute("service", "install", "--help"));
        assertTrue(output.toString().contains("--bundle"));
        assertTrue(output.toString().contains("1.0 bundle"));

        output.getBuffer().setLength(0);
        assertEquals(0, root.execute("service", "uninstall", "--help"));
        assertTrue(output.toString().contains("--dry-run"));
    }

    @Test
    void cliVersionMatchesTheVersionProvider() throws Exception {
        String expected = new WizVersionProvider().getVersion()[0];
        StringWriter output = new StringWriter();
        CommandLine command = new CommandLine(new WizCommand());
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("--version"));
        assertEquals(expected, output.toString().trim());

        String implementationVersion = WizVersionProvider.class.getPackage().getImplementationVersion();
        String expectedVersion = implementationVersion == null || implementationVersion.isBlank()
                ? "dev"
                : implementationVersion.trim();
        assertEquals("wiz-spring " + expectedVersion, expected);
    }
}
