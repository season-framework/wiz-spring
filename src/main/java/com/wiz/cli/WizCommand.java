package com.wiz.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;

@Command(
        name = "wiz-java",
        mixinStandardHelpOptions = true,
        version = "wiz-java 0.0.1-SNAPSHOT",
        description = "Java Spring runtime and CLI for WIZ workspaces.",
        subcommands = {
                CreateCommand.class,
                ProjectCommand.class,
                RunCommand.class
        })
public class WizCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        return 0;
    }
}