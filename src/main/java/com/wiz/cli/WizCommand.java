package com.wiz.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;

@Command(
        name = "wiz-spring",
        mixinStandardHelpOptions = true,
        versionProvider = WizVersionProvider.class,
        description = "Java Spring runtime, MCP server, and Codex setup CLI for WIZ workspaces.",
        subcommands = {
                CreateCommand.class,
                BuildCommand.class,
                JarCommand.class,
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
