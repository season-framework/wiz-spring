package com.wiz.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "project",
        mixinStandardHelpOptions = true,
        description = "Manage WIZ projects.",
        subcommands = {
                ProjectCommand.Create.class,
                ProjectCommand.Build.class,
                ProjectCommand.ListProjects.class,
                ProjectCommand.Delete.class,
                ProjectCommand.Export.class
        })
public class ProjectCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        return 0;
    }

    @Command(name = "create", mixinStandardHelpOptions = true, description = "Create a WIZ project.")
    static class Create implements Callable<Integer> {
        @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
        private java.nio.file.Path root;

        @Option(names = "--project", description = "Project name.")
        private String project = "main";

        @Option(names = "--uri", description = "Git repository URI to clone.")
        private String uri;

        @Option(names = "--path", description = "Local source path to copy.")
        private java.nio.file.Path path;

        @Option(names = "--java-stubs", description = "Generate api.java.stub files for imported api.py files.")
        private boolean javaStubs;

        @Option(names = "--skip-build", description = "Create the project without running the default clean build.")
        private boolean skipBuild;

        @Override
        public Integer call() throws Exception {
            com.wiz.core.ProjectService service = projectService(root);
            com.wiz.runtime.ProjectContext context = service.createProject(project, uri, path, javaStubs);
            System.out.println("Project created: " + context.name());
            System.out.println("Path: " + context.root());
            if (skipBuild) {
                return 0;
            }
            System.out.println("Running initial clean build...");
            com.wiz.build.BuildResult result = new com.wiz.build.ProjectBuildService()
                    .build(context, true, "bundle", com.wiz.build.BuildLogger.console());
            System.out.println(result.message());
            System.out.println("Phases: " + String.join(",", result.phases()));
            return result.exitCode();
        }
    }

    @Command(name = "build", mixinStandardHelpOptions = true, description = "Build a WIZ project.")
    static class Build implements Callable<Integer> {
        @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
        private java.nio.file.Path root;

        @Option(names = "--project", description = "Project name.")
        private String project = "main";

        @Option(names = "--clean", description = "Clean generated build and bundle directories first.")
        private boolean clean;

        @Option(names = "--phase", description = "Build phase to run: reconstruct, compile, bundle.")
        private String phase = "bundle";

        @Override
        public Integer call() throws Exception {
            com.wiz.runtime.ProjectContext context = pathService(root).projectContext(project);
            com.wiz.build.BuildResult result = new com.wiz.build.ProjectBuildService()
                    .build(context, clean, phase, com.wiz.build.BuildLogger.console());
            System.out.println(result.message());
            System.out.println("Phases: " + String.join(",", result.phases()));
            return result.exitCode();
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "List WIZ projects.")
    static class ListProjects implements Callable<Integer> {
        @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
        private java.nio.file.Path root;

        @Override
        public Integer call() throws Exception {
            for (String project : projectService(root).listProjects()) {
                System.out.println(project);
            }
            return 0;
        }
    }

    @Command(name = "delete", mixinStandardHelpOptions = true, description = "Delete a WIZ project.")
    static class Delete implements Callable<Integer> {
        @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
        private java.nio.file.Path root;

        @Option(names = "--project", description = "Project name.", required = true)
        private String project;

        @Override
        public Integer call() throws Exception {
            projectService(root).deleteProject(project);
            System.out.println("Project deleted: " + project);
            return 0;
        }
    }

    @Command(name = "export", mixinStandardHelpOptions = true, description = "Export a WIZ project as a .wizproject archive.")
    static class Export implements Callable<Integer> {
        @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
        private java.nio.file.Path root;

        @Option(names = "--project", description = "Project name.")
        private String project = "main";

        @Option(names = "--output", description = "Output .wizproject path.")
        private java.nio.file.Path output;

        @Override
        public Integer call() throws Exception {
            java.nio.file.Path archive = projectService(root).exportProject(project, output);
            System.out.println("Project exported: " + project);
            System.out.println("Path: " + archive);
            return 0;
        }
    }

    private static com.wiz.core.ProjectService projectService(java.nio.file.Path root) {
        return new com.wiz.core.ProjectService(pathService(root));
    }

    private static com.wiz.runtime.PathService pathService(java.nio.file.Path root) {
        com.wiz.runtime.PathService detector = new com.wiz.runtime.PathService(java.nio.file.Path.of("."));
        java.nio.file.Path workspaceRoot = root == null
                ? detector.findWorkspaceRoot(java.nio.file.Path.of("."))
                        .orElseThrow(() -> new IllegalArgumentException("WIZ workspace root not found"))
                : root.toAbsolutePath().normalize();
        return new com.wiz.runtime.PathService(workspaceRoot);
    }
}
