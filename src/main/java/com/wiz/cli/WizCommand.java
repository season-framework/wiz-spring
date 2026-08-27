package com.wiz.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;

@Command(
        name = "wiz-spring",
        mixinStandardHelpOptions = true,
        versionProvider = WizVersionProvider.class,
        description = "Create and manage standalone Spring projects.",
        subcommands = {
                CreateCommand.class,
                TemplatesCommand.class,
                ServiceCommand.class,
                CompletionCommand.class
        })
public class WizCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        return 0;
    }
}
