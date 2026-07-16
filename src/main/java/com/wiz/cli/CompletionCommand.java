package com.wiz.cli;

import java.io.PrintWriter;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "completion",
        mixinStandardHelpOptions = true,
        description = "Generate a shell completion script for Bash or Zsh.")
public class CompletionCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "SHELL", description = "Target shell: ${COMPLETION-CANDIDATES}.")
    private Shell shell;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        CommandLine root = spec.root().commandLine();
        String script = switch (shell) {
            // Picocli's generated script selects its Bash or Zsh setup at source time.
            case bash, zsh -> CompletionScriptGenerator.generate(root);
        };
        PrintWriter output = spec.commandLine().getOut();
        output.print(script);
        output.flush();
        return 0;
    }

    private enum Shell {
        bash,
        zsh
    }
}
