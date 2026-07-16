package com.wiz.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;

@Command(
        name = "wiz-spring",
        mixinStandardHelpOptions = true,
        versionProvider = WizVersionProvider.class,
        description = "Java Spring runtime and workspace CLI with built-in MCP and automatic Codex setup.",
        subcommands = {
                CreateCommand.class,
                BuildCommand.class,
                JarCommand.class,
                RunCommand.class,
                BundleCommand.class,
                KillCommand.class,
                ServiceCommand.class,
                McpCommand.class,
                CompletionCommand.class
        })
public class WizCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        return 0;
    }
}
