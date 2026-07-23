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

    @Option(names = "--state", description = "External path to the WIZ Spring MCP state file. Must remain outside the workspace; defaults to per-workspace user state.")
    private Path state;

    @Override
    public Integer call() throws Exception {
        Path workspaceRoot = WorkspaceRootResolver.resolve(root, "WIZ_WORKSPACE", "mcp");
        if (System.console() != null) {
            System.err.println("WIZ Spring MCP server running for workspace: " + workspaceRoot);
            System.err.println("Waiting for JSON-RPC messages on stdin.");
        }
        WizMcpToolService tools = new WizMcpToolService(workspaceRoot, state);
        new WizMcpServer(tools, System.in, System.out).run();
        return 0;
    }
}
