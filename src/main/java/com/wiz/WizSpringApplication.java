package com.wiz;

import com.wiz.cli.WizCommand;
import picocli.CommandLine;

/** Entry point for the standalone project generator and service manager. */
public final class WizSpringApplication {

    private WizSpringApplication() {
    }

    public static void main(String[] args) {
        CommandLine commandLine = new CommandLine(new WizCommand());
        commandLine.setExecutionExceptionHandler((exception, command, parseResult) -> {
            String message = exception.getMessage();
            command.getErr().println("Error: "
                    + (message == null || message.isBlank() ? exception.getClass().getSimpleName() : message));
            return command.getCommandSpec().exitCodeOnExecutionException();
        });
        int exitCode = commandLine.execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
