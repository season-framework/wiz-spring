package com.wiz.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.wiz.mcp.WizMcpServer;
import com.wiz.mcp.WizMcpToolService;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "mcp",
        mixinStandardHelpOptions = true,
        description = "Run the standalone WIZ Spring MCP server over stdio. Use server name 'wiz-spring' in MCP clients.")
public class McpCommand implements Callable<Integer> {

    @Option(names = "--root", description = "WIZ Spring workspace root. Defaults to WIZ_WORKSPACE or auto-detecting from the current directory.")
    private Path root;

    @Option(names = "--state", description = "Path to the WIZ Spring MCP state file. Defaults to <workspace>/.wiz/mcp-state.json.")
    private Path state;

    @Override
    public Integer call() throws Exception {
        WizMcpToolService tools = new WizMcpToolService(root, state);
        new WizMcpServer(tools, System.in, System.out).run();
        return 0;
    }
}
