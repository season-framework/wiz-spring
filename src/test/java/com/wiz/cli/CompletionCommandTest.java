package com.wiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class CompletionCommandTest {

    @Test
    void generatesSourceableCompletionForBashAndZsh() {
        String bash = completionScript("bash");
        assertTrue(bash.startsWith("#!/usr/bin/env bash\n"));
        assertTrue(bash.contains("$BASH_VERSION"));
        assertTrue(bash.contains("_picocli_wiz-spring_completion"));
        assertTrue(bash.contains("SHELL_pos_param_args"));
        assertTrue(bash.contains("\"bash\""));
        assertTrue(bash.contains("\"zsh\""));
        assertTrue(bash.contains("complete -F _complete_wiz-spring"));
        assertTrue(bash.contains("_complete_wiz_spring_with_help"));
        assertTrue(bash.contains("complete -F _complete_wiz_spring_with_help -o default"));
        assertTrue(bash.contains("WIZ_SPRING_COMPLETION_HELP"));
        assertTrue(bash.contains("WIZ_SPRING_COMPLETION_COLOR"));
        assertTrue(bash.contains("NO_COLOR"));
        assertTrue(bash.contains("__WIZ_SPRING_COMPLETION_REUSE_PANEL"));
        assertTrue(bash.contains("__WIZ_SPRING_COMPLETION_SUPPRESS_MATCHES"));
        assertTrue(bash.contains("COMPREPLY=()"));
        assertTrue(bash.contains("compopt +o default"));
        assertTrue(bash.contains("__wiz_spring_completion_cursor_supported"));
        assertTrue(bash.contains("__wiz_spring_completion_reserve_panel"));
        assertTrue(bash.contains("__wiz_spring_completion_input_tail_rows"));
        assertTrue(bash.contains("__wiz_spring_completion_begin_panel"));
        assertTrue(bash.contains("__wiz_spring_completion_end_panel"));
        assertTrue(bash.contains("[[ -t 2"));
        assertTrue(bash.contains("\\033D"));
        assertTrue(bash.contains("\\0337\\033[%dB\\r\\033[J"));
        assertTrue(bash.contains("\\0338"));
        assertTrue(bash.contains("\u001B[1m"));
        assertTrue(bash.contains("\u001B[33m"));
        assertTrue(bash.contains("Create a WIZ Spring workspace with automatic .codex"));
        assertTrue(bash.contains("--runtime-jar=<runtimeJar>"));
        assertFalse(bash.contains("Usage: wiz-spring codex"));
        assertTrue(bash.contains("Usage: wiz-spring create"));
        assertTrue(bash.contains("Base Java package for generated source"));
        assertTrue(bash.contains("Generate a shell completion script for Bash or Zsh."));

        String zsh = completionScript("zsh");
        assertTrue(zsh.contains("$ZSH_VERSION"));
        assertTrue(zsh.contains("autoload -U +X compinit && compinit"));
        assertTrue(zsh.contains("autoload -U +X bashcompinit && bashcompinit"));
        assertTrue(zsh.contains("complete -F _complete_wiz-spring"));
        assertTrue(zsh.contains("_complete_wiz_spring_with_help"));
        assertTrue(zsh.contains("_complete_wiz_spring_zsh_with_help"));
        assertTrue(zsh.contains("__wiz_spring_completion_collect_zsh_matches"));
        assertTrue(zsh.contains("compdef _complete_wiz_spring_zsh_with_help"));
        assertTrue(zsh.contains("compadd -X"));
        assertTrue(zsh.contains("%{\u001B[1m%}"));
        assertTrue(zsh.contains("Usage: wiz-spring service"));
    }

    @Test
    void rejectsUnsupportedShell() {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine command = new CommandLine(new WizCommand());
        command.setOut(new PrintWriter(output));
        command.setErr(new PrintWriter(error));

        assertEquals(2, command.execute("completion", "fish"));
        assertTrue(output.toString().isEmpty());
    }

    private String completionScript(String shell) {
        StringWriter output = new StringWriter();
        CommandLine command = new CommandLine(new WizCommand());
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("completion", shell));
        return output.toString();
    }
}
