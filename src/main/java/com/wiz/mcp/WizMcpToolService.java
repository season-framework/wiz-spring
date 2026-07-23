package com.wiz.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.wiz.build.BuildLogger;
import com.wiz.build.BuildResult;
import com.wiz.build.CommandResult;
import com.wiz.build.ProjectBuildLayout;
import com.wiz.build.ProjectBuildService;
import com.wiz.build.StandaloneProjectJarService;
import com.wiz.core.ProjectScaffoldService;
import com.wiz.core.ProjectService;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.SafePath;
import com.wiz.runtime.WorkspaceRuntimePaths;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public class WizMcpToolService {

    private static final String VERSION = "3.0.0";
    private static final Set<String> HIDDEN_TREE_NAMES = Set.of(".git", "node_modules", "__pycache__", ".angular");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path explicitStatePath;
    private final boolean rootLocked;
    private Path workspaceRoot;
    private Path statePath;

    public WizMcpToolService(Path workspaceRoot, Path statePath) {
        this.rootLocked = workspaceRoot != null;
        this.workspaceRoot = initialWorkspaceRoot(workspaceRoot);
        this.explicitStatePath = statePath == null ? envPath("WIZ_STATE_PATH") : statePath.toAbsolutePath().normalize();
        validateExplicitStatePath();
        loadState();
        if (this.workspaceRoot == null) {
            this.workspaceRoot = new PathService(Path.of(".")).findWorkspaceRoot(Path.of(".")).orElse(null);
        }
        validateExplicitStatePath();
    }

    public List<Map<String, Object>> toolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool("wiz_workspace_status", "Get current Spring WIZ workspace state and source paths.", schema()));
        tools.add(tool("wiz_workspace_list_dir", "List a directory relative to the WIZ workspace root.", schema()));
        tools.add(tool("wiz_workspace_read_file", "Read a UTF-8 file relative to the WIZ workspace root.", schema("relativePath")));
        tools.add(tool("wiz_workspace_write_file", "Write a UTF-8 file relative to the WIZ workspace root.", schema("relativePath", "content")));
        tools.add(tool("wiz_workspace_create_dir", "Create a directory relative to the WIZ workspace root.", schema("relativePath")));
        tools.add(tool("wiz_workspace_delete", "Delete a file or directory relative to the WIZ workspace root.", schema("relativePath")));
        tools.add(tool("wiz_workspace_rename", "Rename or move a file or directory relative to the WIZ workspace root.", schema("oldRelativePath", "newRelativePath")));

        tools.add(tool("wiz_app_info", "Get Spring WIZ app information, app counts, package list, and paths.", schema()));
        tools.add(tool("wiz_app_build", "Build the Spring WIZ app.", schema()));
        tools.add(tool("wiz_app_jar", "Package the built Spring WIZ app as a standalone executable jar.", schema()));
        tools.add(tool("wiz_app_dependency_info", "Inspect Spring WIZ app dependency entry points: pom.xml, lib jars, resolved Maven jars, and Angular package.json.", schema()));
        tools.add(tool("wiz_app_structure", "Get a directory tree of the app src directory.", schema()));
        tools.add(tool("wiz_app_list_dir", "List a directory relative to the app root.", schema()));
        tools.add(tool("wiz_app_read_file", "Read a UTF-8 file relative to the app root.", schema("relativePath")));
        tools.add(tool("wiz_app_write_file", "Write a UTF-8 file relative to the app root.", schema("relativePath", "content")));
        tools.add(tool("wiz_app_create_dir", "Create a directory relative to the app root.", schema("relativePath")));
        tools.add(tool("wiz_app_delete", "Delete a file or directory relative to the app root.", schema("relativePath")));
        tools.add(tool("wiz_app_rename", "Rename or move a file or directory relative to the app root.", schema("oldRelativePath", "newRelativePath")));
        tools.add(tool("wiz_app_search_apps", "Search source and portal apps by keyword.", schema("query")));
        tools.add(tool("wiz_app_npm_list", "List Angular npm dependencies for src/angular/package.json.", schema()));
        tools.add(tool("wiz_app_npm_install", "Install Angular npm dependencies for src/angular/package.json.", schema("packages")));
        tools.add(tool("wiz_app_npm_uninstall", "Uninstall Angular npm dependencies for src/angular/package.json.", schema("packages")));

        tools.add(tool("wiz_source_list_apps", "List source apps and routes from src/app and src/route.", schema()));
        tools.add(tool("wiz_source_app_info", "Read app.json and files for a source app or route.", schema("appPath")));
        tools.add(tool("wiz_source_create_app", "Create a Spring WIZ source app skeleton.", schema("appType", "namespace")));
        tools.add(tool("wiz_source_create_route", "Create a Spring WIZ source route skeleton.", schema("id")));
        tools.add(tool("wiz_source_update_app", "Merge updates into an app.json file.", schema("appPath", "updates")));
        tools.add(tool("wiz_source_delete_app", "Delete a source app or route directory.", schema("appPath")));
        tools.add(tool("wiz_source_list_files", "List files in an app or route directory.", schema("appPath")));
        tools.add(tool("wiz_source_read_file", "Read a file in an app or route directory.", schema("appPath", "fileName")));
        tools.add(tool("wiz_source_write_file", "Write a file in an app or route directory.", schema("appPath", "fileName", "content")));
        tools.add(tool("wiz_source_delete_file", "Delete a file in an app or route directory.", schema("appPath", "fileName")));
        tools.add(tool("wiz_source_rename_file", "Rename a file in an app or route directory.", schema("appPath", "oldName", "newName")));
        tools.add(tool("wiz_source_list_controllers", "List Java controller hooks from src/controller.", schema()));
        tools.add(tool("wiz_source_create_controller", "Create a Java controller hook under src/controller.", schema("controller")));
        tools.add(tool("wiz_source_delete_controller", "Delete a Java controller hook from src/controller.", schema("controller")));
        tools.add(tool("wiz_source_list_layouts", "List source layout apps.", schema()));

        tools.add(tool("wiz_package_list", "List portal packages from src/portal.", schema()));
        tools.add(tool("wiz_package_create", "Create a Spring WIZ portal package skeleton.", schema("namespace")));
        tools.add(tool("wiz_package_delete", "Delete a Spring WIZ portal package.", schema("packageName")));
        tools.add(tool("wiz_package_export", "Export a portal package as a .wizpkg archive.", schema("packageName")));
        tools.add(tool("wiz_package_list_apps", "List portal package apps and routes.", schema("packageName")));
        tools.add(tool("wiz_package_app_info", "Read app.json and files for a portal app or route.", schema("appPath")));
        tools.add(tool("wiz_package_create_app", "Create a portal package app skeleton.", schema("packageName", "namespace")));
        tools.add(tool("wiz_package_create_route", "Create a portal package route skeleton.", schema("packageName", "id")));
        tools.add(tool("wiz_package_update_app", "Merge updates into a portal app.json file.", schema("appPath", "updates")));
        tools.add(tool("wiz_package_delete_app", "Delete a portal app or route directory.", schema("appPath")));
        tools.add(tool("wiz_package_list_files", "List files in a portal app or route directory.", schema("appPath")));
        tools.add(tool("wiz_package_read_file", "Read a file in a portal app or route directory.", schema("appPath", "fileName")));
        tools.add(tool("wiz_package_write_file", "Write a file in a portal app or route directory.", schema("appPath", "fileName", "content")));
        tools.add(tool("wiz_package_delete_file", "Delete a file in a portal app or route directory.", schema("appPath", "fileName")));
        tools.add(tool("wiz_package_rename_file", "Rename a file in a portal app or route directory.", schema("appPath", "oldName", "newName")));
        tools.add(tool("wiz_package_list_controllers", "List Java controller hooks from a portal package.", schema("packageName")));
        tools.add(tool("wiz_package_create_controller", "Create a Java controller hook under a portal package.", schema("packageName", "controller")));
        tools.add(tool("wiz_package_delete_controller", "Delete a Java controller hook from a portal package.", schema("packageName", "controller")));
        return tools;
    }

    public Map<String, Object> callTool(String name, Map<String, Object> args) throws Exception {
        loadState();
        Object data = switch (name) {
            case "wiz_workspace_status" -> workspaceStatus();
            case "wiz_workspace_list_dir" -> listDir(requireWorkspaceRoot(), stringArg(args, "relativePath", ""));
            case "wiz_workspace_read_file" -> readFile(requireWorkspaceRoot(), stringArg(args, "relativePath"), args);
            case "wiz_workspace_write_file" -> writeFile(requireWorkspaceRoot(), stringArg(args, "relativePath"), stringArg(args, "content"));
            case "wiz_workspace_create_dir" -> createDir(requireWorkspaceRoot(), stringArg(args, "relativePath"));
            case "wiz_workspace_delete" -> deletePath(requireWorkspaceRoot(), stringArg(args, "relativePath"));
            case "wiz_workspace_rename" -> renamePath(requireWorkspaceRoot(), stringArg(args, "oldRelativePath"), stringArg(args, "newRelativePath"));
            case "wiz_app_info" -> projectInfo(projectName(args));
            case "wiz_app_build" -> projectBuild(projectName(args), boolArg(args, "clean", false));
            case "wiz_app_jar" -> projectJar(args);
            case "wiz_app_dependency_info" -> projectDependencyInfo(projectName(args));
            case "wiz_app_structure" -> projectStructure(projectName(args), intArg(args, "maxDepth", 4), stringArg(args, "subPath", ""));
            case "wiz_app_list_dir" -> listDir(workspaceContext().root(), stringArg(args, "relativePath", ""));
            case "wiz_app_read_file" -> readFile(workspaceContext().root(), stringArg(args, "relativePath"), args);
            case "wiz_app_write_file" -> writeFile(workspaceContext().root(), stringArg(args, "relativePath"), stringArg(args, "content"));
            case "wiz_app_create_dir" -> createDir(workspaceContext().root(), stringArg(args, "relativePath"));
            case "wiz_app_delete" -> deletePath(workspaceContext().root(), stringArg(args, "relativePath"));
            case "wiz_app_rename" -> renamePath(workspaceContext().root(), stringArg(args, "oldRelativePath"), stringArg(args, "newRelativePath"));
            case "wiz_app_search_apps" -> projectSearchApps(projectName(args), stringArg(args, "query"));
            case "wiz_app_pip_list" -> pipUnsupported();
            case "wiz_app_pip_install" -> pipUnsupported();
            case "wiz_app_pip_uninstall" -> pipUnsupported();
            case "wiz_app_npm_list" -> npmList(projectName(args));
            case "wiz_app_npm_install" -> npmInstall(projectName(args), stringListArg(args, "packages"), boolArg(args, "dev", false));
            case "wiz_app_npm_uninstall" -> npmUninstall(projectName(args), stringListArg(args, "packages"));
            case "wiz_source_list_apps" -> sourceListApps(projectName(args), stringArg(args, "appType", "all"));
            case "wiz_source_app_info" -> appInfo(stringArg(args, "appPath"));
            case "wiz_source_create_app" -> sourceCreateApp(args);
            case "wiz_source_create_route" -> sourceCreateRoute(args);
            case "wiz_source_update_app" -> updateApp(stringArg(args, "appPath"), mapArg(args, "updates"));
            case "wiz_source_delete_app" -> deleteApp(stringArg(args, "appPath"));
            case "wiz_source_list_files" -> listAppFiles(stringArg(args, "appPath"));
            case "wiz_source_read_file" -> readAppFile(stringArg(args, "appPath"), stringArg(args, "fileName"), args);
            case "wiz_source_write_file" -> writeAppFile(stringArg(args, "appPath"), stringArg(args, "fileName"), stringArg(args, "content"));
            case "wiz_source_delete_file" -> deleteAppFile(stringArg(args, "appPath"), stringArg(args, "fileName"));
            case "wiz_source_rename_file" -> renameAppFile(stringArg(args, "appPath"), stringArg(args, "oldName"), stringArg(args, "newName"));
            case "wiz_source_list_controllers" -> listControllers(projectName(args), null);
            case "wiz_source_create_controller" -> createController(projectName(args), null, controllerNameArg(args));
            case "wiz_source_delete_controller" -> deleteController(projectName(args), null, controllerNameArg(args));
            case "wiz_source_list_layouts" -> sourceListLayouts(projectName(args));
            case "wiz_package_list" -> packageList(projectName(args));
            case "wiz_package_create" -> packageCreate(args);
            case "wiz_package_delete" -> packageDelete(projectName(args), stringArg(args, "packageName"));
            case "wiz_package_export" -> packageExport(projectName(args), stringArg(args, "packageName"));
            case "wiz_package_list_apps" -> packageListApps(projectName(args), stringArg(args, "packageName"), stringArg(args, "appType", "all"));
            case "wiz_package_app_info" -> appInfo(stringArg(args, "appPath"));
            case "wiz_package_create_app" -> packageCreateApp(args);
            case "wiz_package_create_route" -> packageCreateRoute(args);
            case "wiz_package_update_app" -> updateApp(stringArg(args, "appPath"), mapArg(args, "updates"));
            case "wiz_package_delete_app" -> deleteApp(stringArg(args, "appPath"));
            case "wiz_package_list_files" -> listAppFiles(stringArg(args, "appPath"));
            case "wiz_package_read_file" -> readAppFile(stringArg(args, "appPath"), stringArg(args, "fileName"), args);
            case "wiz_package_write_file" -> writeAppFile(stringArg(args, "appPath"), stringArg(args, "fileName"), stringArg(args, "content"));
            case "wiz_package_delete_file" -> deleteAppFile(stringArg(args, "appPath"), stringArg(args, "fileName"));
            case "wiz_package_rename_file" -> renameAppFile(stringArg(args, "appPath"), stringArg(args, "oldName"), stringArg(args, "newName"));
            case "wiz_package_list_controllers" -> listControllers(projectName(args), stringArg(args, "packageName"));
            case "wiz_package_create_controller" -> createController(projectName(args), stringArg(args, "packageName"), controllerNameArg(args));
            case "wiz_package_delete_controller" -> deleteController(projectName(args), stringArg(args, "packageName"), controllerNameArg(args));
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
        saveState();
        return jsonResult(data);
    }

    public Map<String, Object> errorResult(String message) {
        return Map.of(
                "content", List.of(Map.of("type", "text", "text", message == null ? "Error" : message)),
                "isError", true);
    }

    private Map<String, Object> jsonResult(Object data) throws IOException {
        return Map.of("content", List.of(Map.of("type", "text", "text", prettyJson(data))));
    }

    private Object workspaceStatus() throws IOException {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("workspacePath", workspaceRoot == null ? null : workspaceRoot.toString());
        data.put("version", VERSION);
        data.put("runtime", "spring");
        LinkedHashMap<String, Object> paths = new LinkedHashMap<>();
        paths.put("workspace", workspaceRoot == null ? null : workspaceRoot.toString());
        paths.put("config", workspaceRoot == null ? null : workspaceRoot.resolve("config").toString());
        paths.put("src", workspaceRoot == null ? null : workspaceRoot.resolve("src").toString());
        paths.put("build", workspaceRoot == null ? null : workspaceRoot.resolve("build").toString());
        paths.put("bundle", workspaceRoot == null ? null : workspaceRoot.resolve("bundle").toString());
        data.put("paths", paths);
        if (workspaceRoot != null) {
            PathService pathService = new PathService(workspaceRoot);
            data.put("javaPackageRoot", pathService.packageRoot());
            pathService.workspaceMetadata().ifPresent(metadata -> data.put("workspaceMetadata", Map.of(
                    "workspace", metadata.workspace(),
                    "formatVersion", metadata.formatVersion(),
                    "runtimeName", metadata.runtimeName(),
                    "runtimeVersion", metadata.runtimeVersion())));
        }
        return data;
    }

    private Object projectInfo(String projectName) throws IOException {
        ProjectContext project = workspaceContext();
        if (!Files.isDirectory(project.root())) {
            throw new NoSuchFileException("App does not exist: " + project.root());
        }
        LinkedHashMap<String, Object> appCounts = new LinkedHashMap<>();
        appCounts.put("page", countApps(project.appRoot(), app -> app.name().startsWith("page.") || "page".equals(app.mode())));
        appCounts.put("component", countApps(project.appRoot(), app -> app.name().startsWith("component.") || "component".equals(app.mode())));
        appCounts.put("layout", countApps(project.appRoot(), app -> app.name().startsWith("layout.") || "layout".equals(app.mode())));
        appCounts.put("route", countDirectories(project.routeRoot()));
        int portalAppCount = 0;
        int portalRouteCount = 0;
        for (String packageName : scaffoldService().listPackages(project.name())) {
            portalAppCount += countDirectories(project.sourceRoot().resolve("portal").resolve(packageName).resolve("app"));
            portalRouteCount += countDirectories(project.sourceRoot().resolve("portal").resolve(packageName).resolve("route"));
        }
        appCounts.put("portalApp", portalAppCount);
        appCounts.put("portalRoute", portalRouteCount);

        LinkedHashMap<String, Object> paths = new LinkedHashMap<>();
        paths.put("workspace", requireWorkspaceRoot().toString());
        paths.put("src", project.sourceRoot().toString());
        paths.put("config", project.configRoot().toString());

        LinkedHashMap<String, Object> fileTypes = new LinkedHashMap<>();
        fileTypes.put("standard", "app.json, view.html/view.pug, view.ts, view.scss, api.java, socket.java");
        fileTypes.put("route", "app.json, route.java");
        fileTypes.put("controller", "Java ControllerHook source under src/controller");

        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("app", "main");
        data.put("runtime", "spring");
        data.put("paths", paths);
        data.put("appCounts", appCounts);
        data.put("packages", packageList(project.name()).get("packages"));
        data.put("fileTypes", fileTypes);
        return data;
    }

    private Object projectBuild(String projectName, boolean clean) throws IOException {
        ProjectContext project = workspaceContext();
        CapturingBuildLogger logger = new CapturingBuildLogger();
        BuildResult result = new ProjectBuildService().build(project, clean, "bundle", logger);
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("success", result.success());
        data.put("exitCode", result.exitCode());
        data.put("message", result.message());
        data.put("phases", result.phases());
        data.put("output", logger.output());
        return data;
    }

    private Object projectJar(Map<String, Object> args) throws IOException {
        String projectName = projectName(args);
        ProjectContext project = workspaceContext();
        if (!boolArg(args, "skipBuild", false)) {
            BuildResult result = new ProjectBuildService().build(project, boolArg(args, "clean", false), "bundle", BuildLogger.quiet());
            if (!result.success()) {
                return Map.of("success", false, "exitCode", result.exitCode(), "message", result.message(), "phases", result.phases());
            }
        }
        Path runtimeJar = runtimeJarPath(stringArg(args, "runtimeJar", null));
        Path output = optionalWorkspacePath(stringArg(args, "output", null));
        StandaloneProjectJarService jarService = new StandaloneProjectJarService();
        Path jar = jarService.packageJar(requireWorkspaceRoot(), project, runtimeJar, output);
        return Map.of(
                "success", true,
                "jar", jar.toString(),
                "checksum", jarService.checksumPath(jar).toString(),
                "app", "main");
    }

    private Object projectDependencyInfo(String projectName) throws IOException {
        ProjectContext project = workspaceContext();
        Path pom = project.root().resolve("pom.xml");
        Path angularPackage = project.sourceRoot().resolve("angular/package.json");
        Path dependencyRoot = ProjectBuildLayout.dependencyRoot(project);
        Path libRoot = project.root().resolve("lib");

        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("app", "main");
        data.put("java", Map.of(
                "pomXml", dependencyFileInfo(pom),
                "resolvedMavenJars", jarList(dependencyRoot),
                "localLibJars", jarList(libRoot)));
        data.put("frontend", Map.of("packageJson", dependencyFileInfo(angularPackage)));
        data.put("runtime", Map.of("corePomXml", dependencyFileInfo(Path.of("pom.xml").toAbsolutePath().normalize())));
        data.put("notes", List.of(
                "Java/Spring dependencies belong in workspace pom.xml.",
                "Resolved Maven dependencies are prepared by build under build/target/dependency.",
                "Workspace-local jars can be placed under lib.",
                "Frontend dependencies belong in src/angular/package.json."));
        return data;
    }

    private Object projectStructure(String projectName, int maxDepth, String subPath) throws IOException {
        ProjectContext project = workspaceContext();
        Path start = subPath == null || subPath.isBlank()
                ? project.sourceRoot()
                : new SafePath(project.sourceRoot()).resolveExisting(subPath);
        return Map.of("basePath", start.toString(), "tree", buildTree(start, Math.max(1, maxDepth), 0));
    }

    private Object projectSearchApps(String projectName, String query) throws IOException {
        String q = required(query, "Query is required").toLowerCase(Locale.ROOT);
        ProjectContext project = workspaceContext();
        List<Map<String, Object>> apps = new ArrayList<>();
        apps.addAll(scanSourceApps(project, "all"));
        for (String packageName : scaffoldService().listPackages(project.name())) {
            apps.addAll(scanApps(project.sourceRoot().resolve("portal").resolve(packageName).resolve("app"), "portal/" + packageName, project.sourceRoot()));
            apps.addAll(scanApps(project.sourceRoot().resolve("portal").resolve(packageName).resolve("route"), "portal/" + packageName + "/route", project.sourceRoot()));
        }
        List<Map<String, Object>> results = apps.stream()
                .filter(app -> app.values().stream()
                        .filter(Objects::nonNull)
                        .map(Object::toString)
                        .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(q)))
                .toList();
        return Map.of("query", query, "results", results, "count", results.size());
    }

    private Object pipUnsupported() {
        return Map.of(
                "success", false,
                "supported", false,
                "message", "Python pip packages are not managed by WIZ Spring. Use project pom.xml or src/angular/package.json as appropriate.");
    }

    private Object npmList(String projectName) throws IOException {
        Map<String, Object> packageJson = scaffoldService().npmList(projectName);
        List<Map<String, Object>> packages = new ArrayList<>();
        addNpmSection(packages, packageJson.get("dependencies"), false);
        addNpmSection(packages, packageJson.get("devDependencies"), true);
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("packages", packages);
        data.put("count", packages.size());
        data.put("dependencies", packageJson.getOrDefault("dependencies", Map.of()));
        data.put("devDependencies", packageJson.getOrDefault("devDependencies", Map.of()));
        data.put("cwd", workspaceContext().sourceRoot().resolve("angular").toString());
        return data;
    }

    private Object npmInstall(String projectName, List<String> packages, boolean dev) throws IOException, InterruptedException {
        if (packages.isEmpty()) {
            throw new IllegalArgumentException("No packages specified");
        }
        List<String> outputs = new ArrayList<>();
        boolean success = true;
        for (String packageName : packages) {
            CommandResult result = scaffoldService().npmInstall(projectName, packageName, null, dev, BuildLogger.quiet());
            success &= result.success();
            outputs.add(result.summary());
        }
        return Map.of("success", success, "packages", packages, "dev", dev, "output", String.join(System.lineSeparator(), outputs));
    }

    private Object npmUninstall(String projectName, List<String> packages) throws IOException, InterruptedException {
        if (packages.isEmpty()) {
            throw new IllegalArgumentException("No packages specified");
        }
        List<String> outputs = new ArrayList<>();
        boolean success = true;
        for (String packageName : packages) {
            CommandResult result = scaffoldService().npmUninstall(projectName, packageName, BuildLogger.quiet());
            success &= result.success();
            outputs.add(result.summary());
        }
        return Map.of("success", success, "packages", packages, "output", String.join(System.lineSeparator(), outputs));
    }

    private Object sourceListApps(String projectName, String appType) throws IOException {
        List<Map<String, Object>> apps = scanSourceApps(workspaceContext(), appType);
        return Map.of("apps", apps, "count", apps.size());
    }

    private Object appInfo(String appPath) throws IOException {
        Path app = resolveAppPath(required(appPath, "App path is required"));
        Path appJson = app.resolve("app.json");
        if (!Files.isRegularFile(appJson)) {
            throw new NoSuchFileException("app.json not found at " + app);
        }
        LinkedHashMap<String, Object> data = readJson(appJson);
        data.put("path", app.toString());
        data.put("files", appFiles(app));
        return data;
    }

    private Object sourceCreateApp(Map<String, Object> args) throws IOException {
        String projectName = projectName(args);
        String appType = stringArg(args, "appType", "page");
        String namespace = stringArg(args, "namespace");
        String appId = appId(appType, namespace);
        Path app = scaffoldService().createApp(projectName, null, appId, "pug", appType);
        LinkedHashMap<String, Object> updates = metadataUpdates(args, "title", "category", "controller", "layout", "viewuri");
        if (!updates.isEmpty()) {
            updateJson(app.resolve("app.json"), updates);
        }
        return Map.of("success", true, "appPath", app.toString(), "appJson", readJson(app.resolve("app.json")));
    }

    private Object sourceCreateRoute(Map<String, Object> args) throws IOException {
        String projectName = projectName(args);
        String id = stringArg(args, "id");
        Path route = scaffoldService().createRoute(projectName, null, id, stringArg(args, "routePath", ""), null);
        LinkedHashMap<String, Object> updates = metadataUpdates(args, "title");
        if (!updates.isEmpty()) {
            updateJson(route.resolve("app.json"), updates);
        }
        return Map.of("success", true, "routePath", route.toString(), "appJson", readJson(route.resolve("app.json")));
    }

    private Object updateApp(String appPath, Map<String, Object> updates) throws IOException {
        if (updates.isEmpty()) {
            throw new IllegalArgumentException("Updates are required");
        }
        Path app = resolveAppPath(required(appPath, "App path is required"));
        Path appJson = app.resolve("app.json");
        LinkedHashMap<String, Object> appData = updateJson(appJson, updates);
        return Map.of("success", true, "appJson", appData);
    }

    private Object deleteApp(String appPath) throws IOException {
        Path app = resolveAppPath(required(appPath, "App path is required"));
        deleteTree(app);
        return Map.of("success", true, "deletedPath", app.toString());
    }

    private Object listAppFiles(String appPath) throws IOException {
        Path app = resolveAppPath(required(appPath, "App path is required"));
        List<Map<String, Object>> files = appFiles(app);
        return Map.of("appPath", app.toString(), "files", files, "count", files.size());
    }

    private Object readAppFile(String appPath, String fileName, Map<String, Object> args) throws IOException {
        Path app = resolveAppPath(required(appPath, "App path is required"));
        Path file = new SafePath(app).resolve(required(fileName, "File name is required"));
        if (!Files.exists(file)) {
            return Map.of("exists", false, "content", "", "fileName", fileName);
        }
        if (Files.isDirectory(file)) {
            throw new IllegalArgumentException("Path is a directory: " + file);
        }
        return readFileData(file, args);
    }

    private Object writeAppFile(String appPath, String fileName, String content) throws IOException {
        Path app = resolveAppPath(required(appPath, "App path is required"));
        Path file = new SafePath(app).resolveForWrite(required(fileName, "File name is required"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, content == null ? "" : content);
        return Map.of("success", true, "filePath", file.toString(), "size", Files.size(file));
    }

    private Object deleteAppFile(String appPath, String fileName) throws IOException {
        String name = required(fileName, "File name is required");
        if ("app.json".equals(name)) {
            throw new IllegalArgumentException("Cannot delete app.json. Use delete_app to remove the entire app.");
        }
        Path app = resolveAppPath(required(appPath, "App path is required"));
        Path file = new SafePath(app).resolveExisting(name);
        if (Files.isDirectory(file)) {
            throw new IllegalArgumentException("Path is a directory: " + file);
        }
        Files.delete(file);
        return Map.of("success", true, "deletedFile", file.toString());
    }

    private Object renameAppFile(String appPath, String oldName, String newName) throws IOException {
        Path app = resolveAppPath(required(appPath, "App path is required"));
        Path oldFile = new SafePath(app).resolveExisting(required(oldName, "Old file name is required"));
        Path newFile = new SafePath(app).resolveForWrite(required(newName, "New file name is required"));
        if (Files.exists(newFile)) {
            throw new IllegalArgumentException("Destination already exists: " + newFile);
        }
        Files.createDirectories(newFile.getParent());
        Files.move(oldFile, newFile);
        return Map.of("success", true, "oldPath", oldFile.toString(), "newPath", newFile.toString());
    }

    private Object sourceListLayouts(String projectName) throws IOException {
        List<Map<String, Object>> layouts = new ArrayList<>();
        for (Map<String, Object> app : scanSourceApps(workspaceContext(), "layout")) {
            layouts.add(app);
        }
        return Map.of("layouts", layouts);
    }

    private Map<String, Object> packageList(String projectName) throws IOException {
        ProjectContext project = workspaceContext();
        Path portal = project.sourceRoot().resolve("portal");
        List<Map<String, Object>> packages = new ArrayList<>();
        if (Files.isDirectory(portal)) {
            try (Stream<Path> children = Files.list(portal)) {
                for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                    LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                    data.put("name", child.getFileName().toString());
                    data.put("path", child.toString());
                    Path portalJson = child.resolve("portal.json");
                    if (Files.isRegularFile(portalJson)) {
                        data.putAll(readJson(portalJson));
                    }
                    data.put("subFolders", listDirectoryNames(child));
                    packages.add(data);
                }
            }
        }
        return Map.of("packages", packages);
    }

    private Object packageCreate(Map<String, Object> args) throws IOException {
        String projectName = projectName(args);
        String namespace = firstNonBlank(stringArg(args, "namespace", null), stringArg(args, "packageName", null));
        Path packageRoot = scaffoldService().createPackage(projectName, required(namespace, "Package namespace is required"));
        LinkedHashMap<String, Object> updates = metadataUpdates(args, "title");
        if (!updates.isEmpty()) {
            updateJson(packageRoot.resolve("portal.json"), updates);
        }
        return Map.of("success", true, "namespace", namespace, "packagePath", packageRoot.toString());
    }

    private Object packageDelete(String projectName, String packageName) throws IOException {
        String name = required(packageName, "Package name is required");
        scaffoldService().deletePackage(projectName, name);
        return Map.of("success", true, "packageName", name);
    }

    private Object packageExport(String projectName, String packageName) throws IOException {
        ProjectContext project = workspaceContext();
        String name = required(packageName, "Package name is required");
        Path packageRoot = new SafePath(project.sourceRoot().resolve("portal")).resolveExisting(name);
        if (!Files.isDirectory(packageRoot)) {
            throw new IllegalArgumentException("Package is not a directory: " + name);
        }
        Path exports = requireWorkspaceRoot().resolve("exports");
        Files.createDirectories(exports);
        Path archive = exports.resolve(name + ".wizpkg");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            try (Stream<Path> paths = Files.walk(packageRoot)) {
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    String entryName = packageRoot.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                    zip.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            }
        }
        return Map.of("success", true, "outputPath", archive.toString());
    }

    private Object packageListApps(String projectName, String packageName, String appType) throws IOException {
        ProjectContext project = workspaceContext();
        String name = required(packageName, "Package name is required");
        Path packageRoot = new SafePath(project.sourceRoot().resolve("portal")).resolveExisting(name);
        List<Map<String, Object>> apps = new ArrayList<>();
        if ("all".equals(appType) || "app".equals(appType) || "portal".equals(appType)) {
            apps.addAll(scanApps(packageRoot.resolve("app"), "portal/" + name, project.sourceRoot()));
        }
        if ("all".equals(appType) || "route".equals(appType)) {
            apps.addAll(scanApps(packageRoot.resolve("route"), "portal/" + name + "/route", project.sourceRoot()));
        }
        return Map.of("packageName", name, "apps", apps, "count", apps.size());
    }

    private Object packageCreateApp(Map<String, Object> args) throws IOException {
        String projectName = projectName(args);
        String packageName = stringArg(args, "packageName");
        String namespace = stringArg(args, "namespace");
        Path app = scaffoldService().createApp(projectName, packageName, namespace, "pug", "portal");
        LinkedHashMap<String, Object> updates = metadataUpdates(args, "title", "category", "controller");
        updates.put("mode", "portal");
        if (!updates.isEmpty()) {
            updateJson(app.resolve("app.json"), updates);
        }
        return Map.of("success", true, "appPath", app.toString(), "appJson", readJson(app.resolve("app.json")));
    }

    private Object packageCreateRoute(Map<String, Object> args) throws IOException {
        String projectName = projectName(args);
        String packageName = stringArg(args, "packageName");
        String id = stringArg(args, "id");
        Path route = scaffoldService().createRoute(projectName, packageName, id, stringArg(args, "routePath", ""), null);
        LinkedHashMap<String, Object> updates = metadataUpdates(args, "title");
        if (!updates.isEmpty()) {
            updateJson(route.resolve("app.json"), updates);
        }
        return Map.of("success", true, "routePath", route.toString(), "appJson", readJson(route.resolve("app.json")));
    }

    private Object createController(String projectName, String packageName, String controllerName) throws IOException {
        Path controller = scaffoldService().createController(projectName, packageName, controllerName);
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("success", true);
        data.put("controller", controllerName);
        data.put("path", controller.toString());
        if (packageName != null && !packageName.isBlank()) {
            data.put("packageName", packageName);
        }
        return data;
    }

    private Object deleteController(String projectName, String packageName, String controllerName) throws IOException {
        scaffoldService().deleteController(projectName, packageName, controllerName);
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("success", true);
        data.put("controller", controllerName);
        if (packageName != null && !packageName.isBlank()) {
            data.put("packageName", packageName);
        }
        return data;
    }

    private Object listControllers(String projectName, String packageName) throws IOException {
        ProjectContext project = workspaceContext();
        Path controllerDir = packageName == null || packageName.isBlank()
                ? project.sourceRoot().resolve("controller")
                : new SafePath(project.sourceRoot().resolve("portal")).resolveExisting(packageName).resolve("controller");
        List<Map<String, Object>> controllers = new ArrayList<>();
        if (Files.isDirectory(controllerDir)) {
            try (Stream<Path> children = Files.list(controllerDir)) {
                for (Path file : children.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .toList()) {
                    String fileName = file.getFileName().toString();
                    LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                    data.put("name", fileName.replaceFirst("\\.java$", ""));
                    data.put("file", fileName);
                    data.put("path", file.toString());
                    data.put("size", Files.size(file));
                    controllers.add(data);
                }
            }
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("controllers", controllers);
        result.put("controllerDir", controllerDir.toString());
        if (packageName != null && !packageName.isBlank()) {
            result.put("packageName", packageName);
        }
        return result;
    }

    private Object listDir(Path base, String relativePath) throws IOException {
        Path dir = relativePath == null || relativePath.isBlank()
                ? new SafePath(base).base()
                : new SafePath(base).resolveExisting(relativePath);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + dir);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.sorted(pathComparator()).toList()) {
                LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                item.put("name", child.getFileName().toString());
                item.put("type", Files.isDirectory(child) ? "directory" : "file");
                item.put("size", Files.isRegularFile(child) ? Files.size(child) : 0);
                item.put("modified", Files.getLastModifiedTime(child).toInstant().toString());
                items.add(item);
            }
        }
        return Map.of("path", dir.toString(), "relativePath", relativePath == null ? "" : relativePath, "items", items, "count", items.size());
    }

    private Object readFile(Path base, String relativePath, Map<String, Object> args) throws IOException {
        Path file = new SafePath(base).resolveExisting(required(relativePath, "Relative path is required"));
        if (Files.isDirectory(file)) {
            throw new IllegalArgumentException("Path is a directory: " + file);
        }
        return readFileData(file, args);
    }

    private Object readFileData(Path file, Map<String, Object> args) throws IOException {
        String content = Files.readString(file);
        int startLine = intArg(args, "startLine", 0);
        int endLine = intArg(args, "endLine", 0);
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("exists", true);
        data.put("fileName", file.getFileName().toString());
        data.put("filePath", file.toString());
        if (startLine > 0 || endLine > 0) {
            String[] lines = content.split("\\R", -1);
            int start = Math.max(1, startLine <= 0 ? 1 : startLine);
            int end = Math.min(lines.length, endLine <= 0 ? lines.length : endLine);
            if (end < start) {
                end = start - 1;
            }
            data.put("content", String.join("\n", java.util.Arrays.copyOfRange(lines, start - 1, end)));
            data.put("totalLines", lines.length);
            data.put("range", Map.of("start", start, "end", end));
        } else {
            data.put("content", content);
            data.put("size", content.getBytes(StandardCharsets.UTF_8).length);
            data.put("totalLines", content.split("\\R", -1).length);
        }
        return data;
    }

    private Object writeFile(Path base, String relativePath, String content) throws IOException {
        Path file = new SafePath(base).resolveForWrite(required(relativePath, "Relative path is required"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, content == null ? "" : content);
        return Map.of("success", true, "filePath", file.toString(), "size", Files.size(file));
    }

    private Object createDir(Path base, String relativePath) throws IOException {
        Path dir = new SafePath(base).resolveForWrite(required(relativePath, "Relative path is required"));
        if (Files.exists(dir)) {
            throw new IllegalArgumentException("Path already exists: " + dir);
        }
        Files.createDirectories(dir);
        return Map.of("success", true, "path", dir.toString());
    }

    private Object deletePath(Path base, String relativePath) throws IOException {
        Path target = new SafePath(base).resolveExisting(required(relativePath, "Relative path is required"));
        boolean wasDirectory = Files.isDirectory(target);
        deleteTree(target);
        return Map.of("success", true, "deletedPath", target.toString(), "wasDirectory", wasDirectory);
    }

    private Object renamePath(Path base, String oldRelativePath, String newRelativePath) throws IOException {
        Path oldPath = new SafePath(base).resolveExisting(required(oldRelativePath, "Old relative path is required"));
        Path newPath = new SafePath(base).resolveForWrite(required(newRelativePath, "New relative path is required"));
        if (Files.exists(newPath)) {
            throw new IllegalArgumentException("Destination already exists: " + newPath);
        }
        Files.createDirectories(newPath.getParent());
        Files.move(oldPath, newPath);
        return Map.of("success", true, "oldPath", oldPath.toString(), "newPath", newPath.toString());
    }

    private List<Map<String, Object>> scanSourceApps(ProjectContext project, String appType) throws IOException {
        List<Map<String, Object>> apps = new ArrayList<>();
        String type = appType == null || appType.isBlank() ? "all" : appType;
        if ("all".equals(type) || "page".equals(type) || "component".equals(type) || "layout".equals(type)) {
            apps.addAll(scanApps(project.appRoot(), type, project.sourceRoot()));
        }
        if ("all".equals(type) || "route".equals(type)) {
            apps.addAll(scanApps(project.routeRoot(), "route", project.sourceRoot()));
        }
        return apps;
    }

    private List<Map<String, Object>> scanApps(Path directory, String category, Path sourceRoot) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Map<String, Object>> apps = new ArrayList<>();
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                Path appJson = child.resolve("app.json");
                if (!Files.isRegularFile(appJson)) {
                    continue;
                }
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("name", child.getFileName().toString());
                data.put("path", child.toString());
                data.put("category", category);
                data.put("files", listFileNames(child));
                data.putAll(readJson(appJson));
                data.put("appPath", sourceRoot.relativize(child).toString().replace(java.io.File.separatorChar, '/'));
                if (matchesAppCategory(data, child.getFileName().toString(), category)) {
                    apps.add(data);
                }
            }
        }
        return apps;
    }

    private boolean matchesAppCategory(Map<String, Object> app, String name, String category) {
        if ("all".equals(category) || category.startsWith("portal/")) {
            return true;
        }
        if ("route".equals(category)) {
            return true;
        }
        String mode = String.valueOf(app.getOrDefault("mode", ""));
        return category.equals(mode) || name.startsWith(category + ".");
    }

    private Path resolveAppPath(String appPath) throws IOException {
        ProjectContext project = workspaceContext();
        Path raw = Path.of(appPath);
        Path candidate;
        if (raw.isAbsolute()) {
            candidate = raw.toAbsolutePath().normalize();
            ensureInside(project.root(), candidate);
            return candidate;
        }
        if (appPath.startsWith("src/") || appPath.startsWith("src\\")) {
            candidate = new SafePath(project.root()).resolve(appPath);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        candidate = new SafePath(project.root()).resolve(appPath);
        if (Files.exists(candidate)) {
            return candidate;
        }
        candidate = new SafePath(project.sourceRoot()).resolve(appPath);
        if (Files.exists(candidate)) {
            return candidate;
        }
        candidate = project.appRoot().resolve(appPath).normalize();
        if (Files.exists(candidate)) {
            ensureInside(project.root(), candidate);
            return candidate;
        }
        candidate = project.routeRoot().resolve(appPath).normalize();
        if (Files.exists(candidate)) {
            ensureInside(project.root(), candidate);
            return candidate;
        }
        int dot = appPath.indexOf('.');
        if (dot > 0) {
            String type = appPath.substring(0, dot);
            candidate = project.sourceRoot().resolve(type).resolve(appPath).normalize();
            if (Files.exists(candidate)) {
                ensureInside(project.root(), candidate);
                return candidate;
            }
        }
        Path portal = project.sourceRoot().resolve("portal");
        if (Files.isDirectory(portal)) {
            try (Stream<Path> packages = Files.list(portal)) {
                for (Path pkg : packages.filter(Files::isDirectory).sorted().toList()) {
                    for (String kind : List.of("app", "route")) {
                        candidate = pkg.resolve(kind).resolve(appPath).normalize();
                        if (Files.exists(candidate)) {
                            ensureInside(project.root(), candidate);
                            return candidate;
                        }
                    }
                }
            }
        }
        candidate = project.sourceRoot().resolve(appPath).normalize();
        ensureInside(project.root(), candidate);
        return candidate;
    }

    private LinkedHashMap<String, Object> updateJson(Path file, Map<String, Object> updates) throws IOException {
        LinkedHashMap<String, Object> data = readJson(file);
        data.putAll(updates);
        writeJson(file, data);
        return data;
    }

    private LinkedHashMap<String, Object> readJson(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException(file.toString());
        }
        return objectMapper.readValue(Files.readAllBytes(file), new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private void writeJson(Path file, Map<String, Object> value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }

    private List<Map<String, Object>> appFiles(Path app) throws IOException {
        List<Map<String, Object>> files = new ArrayList<>();
        try (Stream<Path> children = Files.list(app)) {
            for (Path child : children.sorted(pathComparator()).toList()) {
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("name", child.getFileName().toString());
                data.put("size", Files.isRegularFile(child) ? Files.size(child) : 0);
                data.put("isDirectory", Files.isDirectory(child));
                files.add(data);
            }
        }
        return files;
    }

    private Map<String, Object> buildTree(Path directory, int maxDepth, int currentDepth) throws IOException {
        if (currentDepth >= maxDepth) {
            return Map.of("...", "max depth reached");
        }
        LinkedHashMap<String, Object> tree = new LinkedHashMap<>();
        if (!Files.isDirectory(directory)) {
            return tree;
        }
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.sorted(pathComparator()).toList()) {
                String name = child.getFileName().toString();
                if (HIDDEN_TREE_NAMES.contains(name)) {
                    continue;
                }
                if (Files.isDirectory(child)) {
                    tree.put(name + "/", buildTree(child, maxDepth, currentDepth + 1));
                } else {
                    tree.put(name, Files.size(child));
                }
            }
        }
        return tree;
    }

    private void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    private void addNpmSection(List<Map<String, Object>> packages, Object value, boolean dev) {
        if (value instanceof Map<?, ?> map) {
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                    .forEach(entry -> packages.add(Map.of(
                            "name", entry.getKey().toString(),
                            "version", entry.getValue() == null ? "" : entry.getValue().toString(),
                            "dev", dev)));
        }
    }

    private int countDirectories(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> children = Files.list(directory)) {
            return (int) children.filter(Files::isDirectory).count();
        }
    }

    private int countApps(Path directory, Predicate<AppSummary> predicate) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        int count = 0;
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                LinkedHashMap<String, Object> app = Files.isRegularFile(child.resolve("app.json"))
                        ? readJson(child.resolve("app.json"))
                        : new LinkedHashMap<>();
                AppSummary summary = new AppSummary(child.getFileName().toString(), String.valueOf(app.getOrDefault("mode", "")));
                if (predicate.test(summary)) {
                    count++;
                }
            }
        }
        return count;
    }

    private List<String> listDirectoryNames(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(directory)) {
            return children.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private List<String> listFileNames(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(directory)) {
            return children.map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private Comparator<Path> pathComparator() {
        return (left, right) -> {
            boolean leftDir = Files.isDirectory(left);
            boolean rightDir = Files.isDirectory(right);
            if (leftDir != rightDir) {
                return leftDir ? -1 : 1;
            }
            return left.getFileName().toString().compareToIgnoreCase(right.getFileName().toString());
        };
    }

    private PathService paths() {
        return new PathService(requireWorkspaceRoot());
    }

    private ProjectService projectService() {
        return new ProjectService(paths());
    }

    private ProjectScaffoldService scaffoldService() {
        return new ProjectScaffoldService(paths());
    }

    private ProjectContext workspaceContext() {
        return paths().workspaceContext();
    }

    private Path requireWorkspaceRoot() {
        if (workspaceRoot == null) {
            throw new IllegalStateException("Workspace path not set. Pass --root or WIZ_WORKSPACE.");
        }
        return workspaceRoot;
    }

    private Path resolveExternalOrWorkspacePath(String value) {
        Path path = Path.of(value);
        if (path.isAbsolute()) {
            return path.toAbsolutePath().normalize();
        }
        return requireWorkspaceRoot().resolve(path).normalize();
    }

    private Path optionalWorkspacePath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return resolveExternalOrWorkspacePath(value);
    }

    private Path runtimeJarPath(String value) {
        Path runtime = value == null || value.isBlank()
                ? currentRuntimePath()
                : Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(runtime)) {
            throw new IllegalArgumentException("Runtime jar path is required when MCP is not running from an executable jar: " + runtime);
        }
        return runtime;
    }

    private Path currentRuntimePath() {
        String classPath = System.getProperty("java.class.path", "");
        if (!classPath.isBlank() && !classPath.contains(java.io.File.pathSeparator)) {
            Path candidate = Path.of(classPath).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        try {
            return Path.of(WizMcpToolService.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalStateException("Failed to resolve current runtime jar path", exception);
        }
    }

    private Map<String, Object> dependencyFileInfo(Path file) throws IOException {
        LinkedHashMap<String, Object> info = new LinkedHashMap<>();
        info.put("path", file.toString());
        info.put("exists", Files.isRegularFile(file));
        if (Files.isRegularFile(file)) {
            info.put("size", Files.size(file));
            info.put("modified", Files.getLastModifiedTime(file).toInstant().toString());
        }
        return info;
    }

    private List<Map<String, Object>> jarList(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Map<String, Object>> jars = new ArrayList<>();
        try (Stream<Path> children = Files.list(directory)) {
            for (Path jar : children
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList()) {
                LinkedHashMap<String, Object> data = new LinkedHashMap<>();
                data.put("name", jar.getFileName().toString());
                data.put("path", jar.toString());
                data.put("size", Files.size(jar));
                jars.add(data);
            }
        }
        return jars;
    }

    private Path initialWorkspaceRoot(Path root) {
        if (root != null) {
            return root.toAbsolutePath().normalize();
        }
        Path envRoot = envPath("WIZ_WORKSPACE");
        return envRoot == null ? null : envRoot;
    }

    private Path envPath(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private void loadState() {
        Path candidate = findStatePath();
        if (candidate == null || !Files.isRegularFile(candidate)) {
            return;
        }
        try {
            LinkedHashMap<String, Object> raw = objectMapper.readValue(Files.readAllBytes(candidate), new TypeReference<LinkedHashMap<String, Object>>() {
            });
            statePath = candidate;
            Map<String, Object> session = latestSession(raw);
            if (session == null) {
                session = raw;
            }
            Object root = session.get("workspacePath");
            if (!rootLocked && root != null && !root.toString().isBlank()) {
                workspaceRoot = Path.of(root.toString()).toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
        }
    }

    private void saveState() {
        Path target = findStatePath();
        if (target == null) {
            return;
        }
        Path temporary = null;
        try {
            target = prepareStateTarget(target);
            Path lockPath = target.resolveSibling(target.getFileName() + ".lock");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                WorkspaceRuntimePaths.secureFile(lockPath);
                try (FileLock ignored = channel.lock()) {
                    LinkedHashMap<String, Object> raw = readState(target);
                    Object sessionsValue = raw.get("sessions");
                    LinkedHashMap<String, Object> sessions;
                    if (sessionsValue instanceof Map<?, ?> map) {
                        sessions = new LinkedHashMap<>();
                        map.forEach((key, value) -> sessions.put(String.valueOf(key), value));
                    } else {
                        sessions = new LinkedHashMap<>();
                        raw.put("sessions", sessions);
                    }
                    String sessionId = latestSessionId(sessions);
                    if (sessionId == null) {
                        sessionId = "_mcp";
                    }
                    LinkedHashMap<String, Object> session = new LinkedHashMap<>();
                    Object existing = sessions.get(sessionId);
                    if (existing instanceof Map<?, ?> map) {
                        map.forEach((key, value) -> session.put(String.valueOf(key), value));
                    }
                    session.put("workspacePath", requireWorkspaceRoot().toString());
                    session.put("lastUsed", Instant.now().toEpochMilli());
                    sessions.put(sessionId, session);
                    temporary = Files.createTempFile(target.getParent(), "mcp-state-", ".json.tmp");
                    Files.writeString(temporary,
                            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(raw) + "\n");
                    WorkspaceRuntimePaths.secureFile(temporary);
                    moveState(temporary, target);
                    temporary = null;
                    WorkspaceRuntimePaths.secureFile(target);
                    statePath = target;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private LinkedHashMap<String, Object> readState(Path target) {
        if (!Files.isRegularFile(target)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(Files.readAllBytes(target), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private Path prepareStateTarget(Path target) throws IOException {
        Path root = workspaceRoot;
        if (root != null) {
            WorkspaceRuntimePaths.requireOutsideWorkspace(root, target);
            if (target.toAbsolutePath().normalize().equals(WorkspaceRuntimePaths.mcpState(root))) {
                return WorkspaceRuntimePaths.prepareMcpState(root);
            }
        }
        Files.createDirectories(target.getParent());
        return target;
    }

    private void validateExplicitStatePath() {
        if (workspaceRoot == null || explicitStatePath == null) {
            return;
        }
        try {
            WorkspaceRuntimePaths.requireOutsideWorkspace(workspaceRoot, explicitStatePath);
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }

    private void moveState(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path findStatePath() {
        if (explicitStatePath != null) {
            return explicitStatePath;
        }
        if (statePath != null) {
            return statePath;
        }
        Path root = workspaceRoot;
        if (root == null) {
            root = envPath("WIZ_WORKSPACE");
        }
        if (root == null) {
            root = new PathService(Path.of(".")).findWorkspaceRoot(Path.of(".")).orElse(null);
        }
        return root == null ? null : WorkspaceRuntimePaths.mcpState(root);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> latestSession(Map<String, Object> raw) {
        Object sessionsValue = raw.get("sessions");
        if (!(sessionsValue instanceof Map<?, ?> sessions)) {
            return null;
        }
        String latestId = latestSessionId((Map<String, Object>) sessions);
        Object latest = latestId == null ? null : sessions.get(latestId);
        if (latest instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> session = new LinkedHashMap<>();
            map.forEach((key, value) -> session.put(String.valueOf(key), value));
            return session;
        }
        return null;
    }

    private String latestSessionId(Map<String, Object> sessions) {
        String latestId = null;
        long latestTime = Long.MIN_VALUE;
        for (Map.Entry<String, Object> entry : sessions.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> session) {
                Object lastUsed = session.get("lastUsed");
                long time = lastUsed instanceof Number number ? number.longValue() : Long.MIN_VALUE;
                if (time > latestTime) {
                    latestTime = time;
                    latestId = entry.getKey();
                }
            }
        }
        return latestId;
    }

    private void ensureInside(Path base, Path candidate) throws IOException {
        Path normalizedBase = Files.exists(base) ? base.toRealPath() : base.toAbsolutePath().normalize();
        Path normalizedCandidate = Files.exists(candidate) ? candidate.toRealPath() : candidate.toAbsolutePath().normalize();
        if (!normalizedCandidate.startsWith(normalizedBase)) {
            throw new IllegalArgumentException("Path escapes base directory");
        }
    }

    private Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema) {
        return Map.of("name", name, "description", description, "inputSchema", inputSchema);
    }

    private Map<String, Object> schema(String... required) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        for (String name : List.of(
                "workspacePath", "relativePath", "oldRelativePath", "newRelativePath", "content",
                "filePath", "clean", "maxDepth", "subPath", "query", "packages", "dev", "global", "outdated",
                "appType", "namespace", "title", "category", "controller", "layout", "viewuri", "id", "routePath",
                "appPath", "updates", "fileName", "oldName", "newName", "packageName", "output", "runtimeJar",
                "skipBuild", "name")) {
            properties.put(name, Map.of("type", "string"));
        }
        properties.put("packages", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("updates", Map.of("type", "object"));
        properties.put("clean", Map.of("type", "boolean"));
        properties.put("dev", Map.of("type", "boolean"));
        properties.put("global", Map.of("type", "boolean"));
        properties.put("outdated", Map.of("type", "boolean"));
        properties.put("skipBuild", Map.of("type", "boolean"));
        properties.put("maxDepth", Map.of("type", "number"));
        properties.put("startLine", Map.of("type", "number"));
        properties.put("endLine", Map.of("type", "number"));
        return Map.of("type", "object", "properties", properties, "required", List.of(required));
    }

    private String projectName(Map<String, Object> args) {
        return PathService.APP_NAME;
    }

    private String controllerNameArg(Map<String, Object> args) {
        return required(firstNonBlank(
                stringArg(args, "controller", null),
                stringArg(args, "name", null),
                stringArg(args, "namespace", null)), "Controller name is required");
    }

    private String appId(String appType, String namespace) {
        String type = firstNonBlank(appType, "page");
        String name = required(namespace, "Namespace is required");
        if (name.startsWith(type + ".")) {
            return name;
        }
        return type + "." + name;
    }

    private LinkedHashMap<String, Object> metadataUpdates(Map<String, Object> args, String... names) {
        LinkedHashMap<String, Object> updates = new LinkedHashMap<>();
        for (String name : names) {
            Object value = args.get(name);
            if (value != null && !value.toString().isBlank()) {
                updates.put(name, value);
            }
        }
        return updates;
    }

    private String stringArg(Map<String, Object> args, String name) {
        return required(stringArg(args, name, null), name + " is required");
    }

    private String stringArg(Map<String, Object> args, String name, String defaultValue) {
        Object value = args.get(name);
        if (value == null) {
            return defaultValue;
        }
        String text = value.toString();
        return text.isBlank() ? defaultValue : text;
    }

    private boolean boolArg(Map<String, Object> args, String name, boolean defaultValue) {
        Object value = args.get(name);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return value == null ? defaultValue : Boolean.parseBoolean(value.toString());
    }

    private int intArg(Map<String, Object> args, String name, int defaultValue) {
        Object value = args.get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value.toString());
    }

    private List<String> stringListArg(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).filter(item -> !item.isBlank()).toList();
        }
        if (value == null || value.toString().isBlank()) {
            return List.of();
        }
        return List.of(value.toString());
    }

    private Map<String, Object> mapArg(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String prettyJson(Object data) throws IOException {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
    }

    private record AppSummary(String name, String mode) {
    }

    private static final class CapturingBuildLogger implements BuildLogger {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final PrintStream stream = new PrintStream(buffer, true, StandardCharsets.UTF_8);

        @Override
        public void info(String message) {
            stream.println(message);
        }

        @Override
        public void output(String text) {
            stream.print(text);
        }

        String output() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
