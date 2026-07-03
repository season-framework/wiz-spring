package com.wiz.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;

@Command(
        name = "wiz-spring",
        mixinStandardHelpOptions = true,
        version = "wiz-spring 0.0.7",
        description = "Java Spring runtime, MCP server, and Codex setup CLI for WIZ workspaces.",
        subcommands = {
                CreateCommand.class,
                ProjectCommand.class,
                RunCommand.class,
                BundleCommand.class,
                KillCommand.class,
                ServiceCommand.class,
                McpCommand.class,
                CodexCommand.class
        })
public class WizCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        return 0;
    }
}
