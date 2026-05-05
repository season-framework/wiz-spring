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
                ProjectCommand.Export.class,
                ProjectCommand.App.class,
                ProjectCommand.Controller.class,
                ProjectCommand.Route.class,
                ProjectCommand.PackageGroup.class,
                ProjectCommand.Npm.class
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

        @Option(names = "--skip-build", description = "Create the project without running the default clean build.")
        private boolean skipBuild;

        @Override
        public Integer call() throws Exception {
            com.wiz.core.ProjectService service = projectService(root);
            com.wiz.runtime.ProjectContext context = service.createProject(project, uri, path);
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

    @Command(name = "app", mixinStandardHelpOptions = true, description = "Manage WIZ apps.", subcommands = {
            App.ListApps.class,
            App.Create.class,
            App.Delete.class
    })
    static class App implements Callable<Integer> {
        @Override
        public Integer call() {
            return 0;
        }

        @Command(name = "list", mixinStandardHelpOptions = true, description = "List apps.")
        static class ListApps implements Callable<Integer> {
            @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
            private java.nio.file.Path root;

            @Option(names = "--project", description = "Project name.")
            private String project = "main";

            @Option(names = "--package", description = "Portal package name.")
            private String packageName;

            @Override
            public Integer call() throws Exception {
                for (String app : scaffoldService(root).listApps(project, packageName)) {
                    System.out.println(app);
                }
                return 0;
            }
        }

        @Command(name = "create", mixinStandardHelpOptions = true, description = "Create an app.")
        static class Create implements Callable<Integer> {
            @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
            private java.nio.file.Path root;

            @Option(names = "--project", description = "Project name.")
            private String project = "main";

            @Option(names = "--package", description = "Portal package name.")
            private String packageName;

            @Option(names = {"--app", "--namespace"}, description = "App id, for example page.home.", required = true)
            private String app;

            @Option(names = "--engine", description = "Template engine: pug or html.")
            private String engine = "pug";

            @Option(names = "--mode", description = "App mode: page, component, or layout.")
            private String mode = "page";

            @Override
            public Integer call() throws Exception {
                java.nio.file.Path path = scaffoldService(root).createApp(project, packageName, app, engine, mode);
                System.out.println("App created: " + app);
                System.out.println("Path: " + path);
                return 0;
            }
        }

        @Command(name = "delete", mixinStandardHelpOptions = true, description = "Delete an app.")
        static class Delete implements Callable<Integer> {
            @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
            private java.nio.file.Path root;

            @Option(names = "--project", description = "Project name.")
            private String project = "main";

            @Option(names = "--package", description = "Portal package name.")
            private String packageName;

            @Option(names = {"--app", "--namespace"}, description = "App id.", required = true)
            private String app;

            @Override
            public Integer call() throws Exception {
                scaffoldService(root).deleteApp(project, packageName, app);
                System.out.println("App deleted: " + app);
                return 0;
            }
        }
    }

    @Command(name = "controller", mixinStandardHelpOptions = true, description = "Manage WIZ controllers.", subcommands = {
            Controller.ListControllers.class,
            Controller.Create.class,
            Controller.Delete.class
    })
    static class Controller implements Callable<Integer> {
        @Override
        public Integer call() {
            return 0;
        }

        @Command(name = "list", mixinStandardHelpOptions = true, description = "List controllers.")
        static class ListControllers implements Callable<Integer> {
            @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
            private java.nio.file.Path root;

            @Option(names = "--project", description = "Project name.")
            private String project = "main";

            @Option(names = "--package", description = "Portal package name.")
            private String packageName;

            @Override
            public Integer call() throws Exception {
                for (String controller : scaffoldService(root).listControllers(project, packageName)) {
                    System.out.println(controller);
                }
                return 0;
            }
        }

        @Command(name = "create", mixinStandardHelpOptions = true, description = "Create a controller.")
        static class Create implements Callable<Integer> {
            @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
            private java.nio.file.Path root;

            @Option(names = "--project", description = "Project name.")
            private String project = "main";

            @Option(names = "--package", description = "Portal package name.")
            private String packageName;

            @Option(names = {"--controller", "--name", "--namespace"}, description = "Controller name.", required = true)
            private String controller;

            @Override
            public Integer call() throws Exception {
                java.nio.file.Path path = scaffoldService(root).createController(project, packageName, controller);
                System.out.println("Controller created: " + controller);
                System.out.println("Path: " + path);
                return 0;
            }
        }

        @Command(name = "delete", mixinStandardHelpOptions = true, description = "Delete a controller.")
        static class Delete implements Callable<Integer> {
            @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
            private java.nio.file.Path root;

            @Option(names = "--project", description = "Project name.")
            private String project = "main";

            @Option(names = "--package", description = "Portal package name.")
            private String packageName;

            @Option(names = {"--controller", "--name", "--namespace"}, description = "Controller name.", required = true)
            private String controller;

            @Override
            public Integer call() throws Exception {
                scaffoldService(root).deleteController(project, packageName, controller);
                System.out.println("Controller deleted: " + controller);
                return 0;
            }
        }
    }

    @Command(name = "route", mixinStandardHelpOptions = true, description = "Manage WIZ routes.", subcommands = {
            Route.ListRoutes.class,
            Route.Create.class,
            Route.Delete.class
    })
    static class Route implements Callable<Integer> {
        @Override
        public Integer call() {
            return 0;
        }

        @Command(name = "list", mixinStandardHelpOptions = true, description = "List routes.")
        static class ListRoutes implements Callable<Integer> {
            @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
            private java.nio.file.Path root;

            @Option(names = "--project", description = "Project name.")
            private String project = "main";

            @Option(names = "--package", description = "Portal package name.")
            private String packageName;

            @Override
            public Integer call() throws Exception {
                for (String route : scaffoldService(root).listRoutes(project, packageName)) {
                    System.out.println(route);
                }
                return 0;
            }
        }

        @Command(name = "create", mixinStandardHelpOptions = true, description = "Create a route.")
        static class Create implements Callable<Integer> {
            @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
            private java.nio.file.Path root;

            @Option(names = "--project", description = "Project name.")
            private String project = "main";

            @Option(names = "--package", description = "Portal package name.")
            private String packageName;

            @Option(names = {"--route", "--name", "--namespace"}, description = "Route id.", required = true)
            private String route;

            @Option(names = "--path", description = "HTTP route path.")
            private String routePath;

            @Option(names = "--methods", description = "Comma-separated HTTP methods.")
            private String methods;

            @Override
            public Integer call() throws Exception {
                java.nio.file.Path path = scaffoldService(root).createRoute(project, packageName, route, routePath, methods);
                System.out.println("Route created: " + route);
                System.out.println("Path: " + path);
                return 0;
            }
        }

        @Command(name = "delete", mixinStandardHelpOptions = true, description = "Delete a route.")
        static class Delete implements Callable<Integer> {
            @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
            private java.nio.file.Path root;

            @Option(names = "--project", description = "Project name.")
            private String project = "main";

            @Option(names = "--package", description = "Portal package name.")
            private String packageName;

            @Option(names = {"--route", "--name", "--namespace"}, description = "Route id.", required = true)
            private String route;

            @Override
            public Integer call() throws Exception {
                scaffoldService(root).deleteRoute(project, packageName, route);
                System.out.println("Route deleted: " + route);
                return 0;
            }
        }
    }

    @Command(name = "package", mixinStandardHelpOptions = true, description = "Manage portal packages.", subcommands = {
            PackageGroup.ListPackages.class,
            PackageGroup.Create.class,
            PackageGroup.Delete.class
    })
    static class PackageGroup implements Callable<Integer> {
        @Override
        public Integer call() {
            return 0;
        }

        @Command(name = "list", mixinStandardHelpOptions = true, description = "List portal packages.")
        static class ListPackages implements Callable<Integer> {
            @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
            private java.nio.file.Path root;

            @Option(names = "--project", description = "Project name.")
            private String project = "main";

            @Override
            public Integer call() throws Exception {
                for (String packageName : scaffoldService(root).listPackages(project)) {
                    System.out.println(packageName);
                }
                return 0;
            }
        }

        @Command(name = "create", mixinStandardHelpOptions = true, description = "Create a portal package.")
        static class Create implements Callable<Integer> {
            @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
            private java.nio.file.Path root;

            @Option(names = "--project", description = "Project name.")
            private String project = "main";

            @Option(names = {"--package", "--name", "--namespace"}, description = "Portal package name.", required = true)
            private String packageName;

            @Override
            public Integer call() throws Exception {
                java.nio.file.Path path = scaffoldService(root).createPackage(project, packageName);
                System.out.println("Package created: " + packageName);
                System.out.println("Path: " + path);
                return 0;
            }
        }

        @Command(name = "delete", mixinStandardHelpOptions = true, description = "Delete a portal package.")
        static class Delete implements Callable<Integer> {
            @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
            private java.nio.file.Path root;

            @Option(names = "--project", description = "Project name.")
            private String project = "main";

            @Option(names = {"--package", "--name", "--namespace"}, description = "Portal package name.", required = true)
            private String packageName;

            @Override
            public Integer call() throws Exception {
                scaffoldService(root).deletePackage(project, packageName);
                System.out.println("Package deleted: " + packageName);
                return 0;
            }
        }
    }

    @Command(name = "npm", mixinStandardHelpOptions = true, description = "Manage Angular npm dependencies.", subcommands = {
            Npm.ListPackages.class,
            Npm.Install.class,
            Npm.Uninstall.class
    })
    static class Npm implements Callable<Integer> {
        @Override
        public Integer call() {
            return 0;
        }

        @Command(name = "list", mixinStandardHelpOptions = true, description = "List npm dependencies.")
        static class ListPackages implements Callable<Integer> {
            @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
            private java.nio.file.Path root;

            @Option(names = "--project", description = "Project name.")
            private String project = "main";

            @Override
            public Integer call() throws Exception {
                java.util.Map<String, Object> data = scaffoldService(root).npmList(project);
                printDependencySection("Dependencies", data.get("dependencies"));
                printDependencySection("Dev Dependencies", data.get("devDependencies"));
                return 0;
            }

            private void printDependencySection(String title, Object value) {
                System.out.println(title + ":");
                if (value instanceof java.util.Map<?, ?> map) {
                    map.entrySet().stream()
                            .sorted(java.util.Comparator.comparing(entry -> entry.getKey().toString()))
                            .forEach(entry -> System.out.println("  " + entry.getKey() + ": " + entry.getValue()));
                }
            }
        }

        @Command(name = "install", mixinStandardHelpOptions = true, description = "Install npm dependencies.")
        static class Install implements Callable<Integer> {
            @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
            private java.nio.file.Path root;

            @Option(names = "--project", description = "Project name.")
            private String project = "main";

            @Option(names = "--package", description = "Package name.")
            private String packageName;

            @Option(names = {"--version", "-v"}, description = "Package version.")
            private String version;

            @Option(names = {"--dev", "-d"}, description = "Install as dev dependency.")
            private boolean dev;

            @Override
            public Integer call() throws Exception {
                com.wiz.build.CommandResult result = scaffoldService(root).npmInstall(project, packageName, version, dev, com.wiz.build.BuildLogger.console());
                System.out.println(result.summary());
                return result.success() ? 0 : result.exitCode();
            }
        }

        @Command(name = "uninstall", mixinStandardHelpOptions = true, description = "Uninstall an npm dependency.")
        static class Uninstall implements Callable<Integer> {
            @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
            private java.nio.file.Path root;

            @Option(names = "--project", description = "Project name.")
            private String project = "main";

            @Option(names = "--package", description = "Package name.", required = true)
            private String packageName;

            @Override
            public Integer call() throws Exception {
                com.wiz.build.CommandResult result = scaffoldService(root).npmUninstall(project, packageName, com.wiz.build.BuildLogger.console());
                System.out.println(result.summary());
                return result.success() ? 0 : result.exitCode();
            }
        }
    }

    private static com.wiz.core.ProjectService projectService(java.nio.file.Path root) {
        return new com.wiz.core.ProjectService(pathService(root));
    }

    private static com.wiz.core.ProjectScaffoldService scaffoldService(java.nio.file.Path root) {
        return new com.wiz.core.ProjectScaffoldService(pathService(root));
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
