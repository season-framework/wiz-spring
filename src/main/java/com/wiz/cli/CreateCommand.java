package com.wiz.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.wiz.core.WorkspaceService;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "create", mixinStandardHelpOptions = true, description = "Create a new WIZ workspace.")
public class CreateCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "PATH", description = "Workspace path or name.")
    private Path path;

    @Override
    public Integer call() throws Exception {
        WorkspaceService service = new WorkspaceService();
        WorkspaceService.CreatedWorkspace workspace = service.createWorkspace(path);
        System.out.println("Workspace created: " + workspace.root());
        System.out.println("Port: " + workspace.port());
        System.out.println("Run: wiz-spring run --root " + workspace.root() + " --port " + workspace.port());
        return 0;
    }
}
