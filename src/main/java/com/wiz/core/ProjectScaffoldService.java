package com.wiz.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.wiz.build.BuildLogger;
import com.wiz.build.CommandExecutor;
import com.wiz.build.CommandResult;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.SafePath;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public class ProjectScaffoldService {

    private final PathService paths;
    private final ObjectMapper objectMapper;
    private final CommandExecutor commandExecutor;

    public ProjectScaffoldService(PathService paths) {
        this(paths, new ObjectMapper(), new CommandExecutor());
    }

    ProjectScaffoldService(PathService paths, ObjectMapper objectMapper, CommandExecutor commandExecutor) {
        this.paths = paths;
        this.objectMapper = objectMapper;
        this.commandExecutor = commandExecutor;
    }

    public List<String> listApps(String project, String packageName) throws IOException {
        return listDirectories(appBase(projectContext(project), packageName));
    }

    public Path createApp(String project, String packageName, String appId, String engine, String mode) throws IOException {
        ProjectContext context = projectContext(project);
        String id = safeSegment(required(appId, "App id is required"));
        Path appRoot = new SafePath(appBase(context, packageName)).resolveForWrite(id);
        if (Files.exists(appRoot)) {
            throw new IllegalArgumentException("App already exists: " + id);
        }
        Files.createDirectories(appRoot);

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", blankDefault(mode, "page"));
        metadata.put("id", id);
        metadata.put("title", id);
        metadata.put("namespace", id);
        metadata.put("viewuri", "/" + id.replace('.', '/'));
        metadata.put("category", "");
        metadata.put("controller", "");
        metadata.put("template", ProjectJavaNaming.selector(id) + "()");
        metadata.put("runtime", "java");
        metadata.put("api", Map.of("handler", ProjectJavaNaming.appApiHandlerClass(context.name(), id)));
        writeJson(appRoot.resolve("app.json"), metadata);

        Files.writeString(appRoot.resolve("view.ts"), appViewTs());
        String selectedEngine = blankDefault(engine, "pug").toLowerCase(java.util.Locale.ROOT);
        if ("html".equals(selectedEngine)) {
            Files.writeString(appRoot.resolve("view.html"), "<section>\n  <h1>{{title}}</h1>\n</section>\n");
        } else {
            Files.writeString(appRoot.resolve("view.pug"), "section\n  h1 {{title}}\n");
        }
        Files.writeString(appRoot.resolve("view.scss"), "");
        Files.writeString(appRoot.resolve("api.java"), appApiJava(id));
        return appRoot;
    }

    public void deleteApp(String project, String packageName, String appId) throws IOException {
        deleteChild(appBase(projectContext(project), packageName), safeSegment(required(appId, "App id is required")), "App");
    }

    public List<String> listControllers(String project, String packageName) throws IOException {
        return listFiles(controllerBase(projectContext(project), packageName), ".java");
    }

    public Path createController(String project, String packageName, String controllerName) throws IOException {
        ProjectContext context = projectContext(project);
        String name = safeSegment(required(controllerName, "Controller name is required"));
        Path base = controllerBase(context, packageName);
        Files.createDirectories(base);
        String className = suffixedClassName(name, "Controller");
        Path file = new SafePath(base).resolveForWrite(className + ".java");
        if (Files.exists(file)) {
            throw new IllegalArgumentException("Controller already exists: " + name);
        }
        Files.writeString(file, controllerJava(className));
        return file;
    }

    public void deleteController(String project, String packageName, String controllerName) throws IOException {
        ProjectContext context = projectContext(project);
        String name = safeSegment(required(controllerName, "Controller name is required"));
        deleteFile(controllerBase(context, packageName).resolve(suffixedClassName(name, "Controller") + ".java"), "Controller");
    }

    public List<String> listRoutes(String project, String packageName) throws IOException {
        return listDirectories(routeBase(projectContext(project), packageName));
    }

    public Path createRoute(String project, String packageName, String routeName, String routePath, String methods) throws IOException {
        ProjectContext context = projectContext(project);
        String name = safeSegment(required(routeName, "Route name is required"));
        Path routeRoot = new SafePath(routeBase(context, packageName)).resolveForWrite(name);
        if (Files.exists(routeRoot)) {
            throw new IllegalArgumentException("Route already exists: " + name);
        }
        Files.createDirectories(routeRoot);
        String path = blankDefault(routePath, "/" + name);
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("id", name);
        metadata.put("title", name);
        metadata.put("route", path);
        metadata.put("viewuri", "");
        metadata.put("category", "");
        metadata.put("methods", splitMethods(methods));
        metadata.put("handler", ProjectJavaNaming.routeHandlerClass(context.name(), name));
        writeJson(routeRoot.resolve("app.json"), metadata);
        Files.writeString(routeRoot.resolve("route.java"), routeJava(name, path));
        return routeRoot;
    }

    public void deleteRoute(String project, String packageName, String routeName) throws IOException {
        deleteChild(routeBase(projectContext(project), packageName), safeSegment(required(routeName, "Route name is required")), "Route");
    }

    public List<String> listPackages(String project) throws IOException {
        return listDirectories(projectContext(project).sourceRoot().resolve("portal"));
    }

    public Path createPackage(String project, String packageName) throws IOException {
        ProjectContext context = projectContext(project);
        String name = safeSegment(required(packageName, "Package name is required"));
        Path packageRoot = new SafePath(context.sourceRoot().resolve("portal")).resolveForWrite(name);
        if (Files.exists(packageRoot)) {
            throw new IllegalArgumentException("Package already exists: " + name);
        }
        Files.createDirectories(packageRoot);
        for (String directory : List.of("app", "widget", "controller", "route", "model", "assets", "libs", "styles")) {
            Files.createDirectories(packageRoot.resolve(directory));
        }
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("package", name);
        metadata.put("title", name.toUpperCase(java.util.Locale.ROOT));
        metadata.put("version", "1.0.0");
        metadata.put("use_app", true);
        metadata.put("use_widget", true);
        metadata.put("use_route", true);
        metadata.put("use_libs", true);
        metadata.put("use_styles", true);
        metadata.put("use_assets", true);
        metadata.put("use_controller", true);
        metadata.put("use_model", true);
        writeJson(packageRoot.resolve("portal.json"), metadata);
        Files.writeString(packageRoot.resolve("README.md"), "# " + name + "\n\nWIZ portal package.\n");
        return packageRoot;
    }

    public void deletePackage(String project, String packageName) throws IOException {
        deleteChild(projectContext(project).sourceRoot().resolve("portal"), safeSegment(required(packageName, "Package name is required")), "Package");
    }

    public Map<String, Object> npmList(String project) throws IOException {
        Path packageJson = angularPackageJson(projectContext(project));
        if (!Files.isRegularFile(packageJson)) {
            throw new IllegalArgumentException("src/angular/package.json not found");
        }
        return objectMapper.readValue(Files.readAllBytes(packageJson), new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    public CommandResult npmInstall(String project, String packageName, String version, boolean dev, BuildLogger logger) throws IOException, InterruptedException {
        ProjectContext context = projectContext(project);
        Path angularRoot = angularRoot(context);
        if (!Files.isRegularFile(angularRoot.resolve("package.json"))) {
            throw new IllegalArgumentException("src/angular/package.json not found");
        }
        java.util.ArrayList<String> argv = new java.util.ArrayList<>();
        argv.add("npm");
        argv.add("install");
        if (packageName != null && !packageName.isBlank()) {
            argv.add(dev ? "--save-dev" : "--save");
            argv.add(version == null || version.isBlank() ? packageName : packageName + "@" + version);
        }
        return commandExecutor.run("npm-install", paths.root(), angularRoot, argv, java.time.Duration.ofMinutes(10), 256 * 1024, logger);
    }

    public CommandResult npmUninstall(String project, String packageName, BuildLogger logger) throws IOException, InterruptedException {
        ProjectContext context = projectContext(project);
        Path angularRoot = angularRoot(context);
        if (!Files.isRegularFile(angularRoot.resolve("package.json"))) {
            throw new IllegalArgumentException("src/angular/package.json not found");
        }
        String name = required(packageName, "Package name is required");
        return commandExecutor.run("npm-uninstall", paths.root(), angularRoot, List.of("npm", "uninstall", "--save", name), java.time.Duration.ofMinutes(5), 256 * 1024, logger);
    }

    private ProjectContext projectContext(String project) {
        return paths.projectContext(blankDefault(project, "main"));
    }

    private Path appBase(ProjectContext project, String packageName) {
        if (packageName != null && !packageName.isBlank()) {
            return project.sourceRoot().resolve("portal").resolve(safeSegment(packageName)).resolve("app");
        }
        return project.appRoot();
    }

    private Path controllerBase(ProjectContext project, String packageName) {
        if (packageName != null && !packageName.isBlank()) {
            return project.sourceRoot().resolve("portal").resolve(safeSegment(packageName)).resolve("controller");
        }
        return project.sourceRoot().resolve("controller");
    }

    private Path routeBase(ProjectContext project, String packageName) {
        if (packageName != null && !packageName.isBlank()) {
            return project.sourceRoot().resolve("portal").resolve(safeSegment(packageName)).resolve("route");
        }
        return project.routeRoot();
    }

    private Path angularRoot(ProjectContext project) {
        return project.sourceRoot().resolve("angular");
    }

    private Path angularPackageJson(ProjectContext project) {
        return angularRoot(project).resolve("package.json");
    }

    private List<String> listDirectories(Path base) throws IOException {
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(base)) {
            return children.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private List<String> listFiles(Path base, String suffix) throws IOException {
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(base)) {
            return children.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> suffix == null || name.endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    private void deleteChild(Path base, String child, String label) throws IOException {
        Path target = new SafePath(base).resolve(child);
        if (!Files.exists(target)) {
            throw new IllegalArgumentException(label + " does not exist: " + child);
        }
        deleteTree(target);
    }

    private void deleteFile(Path file, String label) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException(label + " does not exist: " + file.getFileName());
        }
        Files.delete(file);
    }

    private void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    private void writeJson(Path file, Map<String, Object> value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }

    private List<String> splitMethods(String methods) {
        if (methods == null || methods.isBlank()) {
            return List.of("GET", "POST");
        }
        return java.util.Arrays.stream(methods.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toUpperCase(java.util.Locale.ROOT))
                .toList();
    }

    private String safeSegment(String value) {
        String segment = required(value, "Path segment is required");
        Path candidate = Path.of(segment);
        if (candidate.isAbsolute()
                || candidate.getNameCount() != 1
                || !candidate.normalize().equals(candidate)
                || segment.contains("\\")
                || ".".equals(segment)
                || "..".equals(segment)) {
            throw new IllegalArgumentException("Value must be a single safe path segment: " + segment);
        }
        return segment;
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String blankDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String suffixedClassName(String value, String suffix) {
        String className = ProjectJavaNaming.className(value);
        return className.endsWith(suffix) ? className : className + suffix;
    }

    private String appViewTs() {
        return "import { OnInit, Input } from '@angular/core';\n\n"
                + "export class Component implements OnInit {\n"
                + "    @Input() title: any;\n\n"
                + "    public async ngOnInit() {\n"
                + "    }\n"
                + "}\n";
    }

    private String appApiJava(String appId) {
        String className = ProjectJavaNaming.className(appId) + "Api";
        return "import java.util.Map;\n"
                + "import com.wiz.runtime.WizContext;\n"
                + "import com.wiz.runtime.WizResult;\n\n"
                + "public final class " + className + " {\n"
                + "    public WizResult status(WizContext wiz) {\n"
                + "        return wiz.response().status(200, Map.of(\"app\", \"" + appId + "\", \"status\", \"ok\"));\n"
                + "    }\n"
                + "}\n";
    }

    private String controllerJava(String className) {
        return "import com.wiz.dispatch.ControllerHook;\n"
                + "import com.wiz.runtime.WizContext;\n"
                + "import com.wiz.runtime.WizResult;\n\n"
                + "public final class " + className + " implements ControllerHook {\n"
                + "    @Override\n"
                + "    public WizResult before(WizContext wiz) {\n"
                + "        wiz.response().data(\"session\", wiz.session().toMap());\n"
                + "        return null;\n"
                + "    }\n"
                + "}\n";
    }

    private String routeJava(String routeId, String routePath) {
        String className = ProjectJavaNaming.className(routeId) + "RouteHandler";
        return "import java.util.Map;\n"
                + "import com.wiz.dispatch.RouteHandler;\n"
                + "import com.wiz.runtime.WizContext;\n"
                + "import com.wiz.runtime.WizResult;\n"
                + "import com.wiz.runtime.WizSegment;\n\n"
                + "public final class " + className + " implements RouteHandler {\n"
                + "    public String routeId() { return \"" + routeId + "\"; }\n\n"
                + "    public WizResult handle(WizContext wiz, WizSegment segment) {\n"
                + "        return wiz.response().status(200, Map.of(\"route\", \"" + routeId + "\", \"path\", \"" + routePath + "\"));\n"
                + "    }\n"
                + "}\n";
    }
}
