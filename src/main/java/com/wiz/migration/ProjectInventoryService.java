package com.wiz.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.wiz.core.ProjectJavaNaming;

import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProjectInventoryService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProjectInventory inventory(Path projectRoot) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path sourceRoot = root.resolve("src");
        if (!Files.isDirectory(sourceRoot)) {
            return ProjectInventory.empty();
        }
        List<String> appJsonFiles = collectByFileName(root, sourceRoot, "app.json");
        List<String> apiPythonFiles = collectByFileName(root, sourceRoot, "api.py");
        return new ProjectInventory(
                appJsonFiles,
                apiPythonFiles,
                collectByFileName(root, sourceRoot, "socket.py"),
                collectUnder(root, sourceRoot.resolve("controller"), ".py"),
                collectUnder(root, sourceRoot.resolve("model"), ".py"),
                collectAll(root, sourceRoot.resolve("route")),
                appJsonFiles.stream().filter(path -> path.startsWith("src/portal/") && path.contains("/app/")).toList(),
                componentMetadata(root, appJsonFiles, apiPythonFiles),
                routeMetadata(root, appJsonFiles),
                portalMetadata(root, sourceRoot),
                List.of());
    }

    public boolean writeReportIfPythonProject(Path projectRoot) throws IOException {
        return writeReportIfPythonProject(projectRoot, false);
    }

    public boolean writeReportIfPythonProject(Path projectRoot, boolean generateJavaStubs) throws IOException {
        ProjectInventory inventory = inventory(projectRoot);
        if (!inventory.hasPythonSource()) {
            return false;
        }
        List<String> generatedStubs = generateJavaStubs ? generateJavaStubs(projectRoot.toAbsolutePath().normalize(), inventory.apiPythonFiles()) : List.of();
        inventory = inventory.withGeneratedStubFiles(generatedStubs);
        Files.writeString(projectRoot.resolve("migration-report.json"), inventory.toJson());
        Files.writeString(projectRoot.resolve("migration-report.md"), inventory.toMarkdown());
        return true;
    }

    private List<String> generateJavaStubs(Path root, List<String> apiPythonFiles) throws IOException {
        ArrayList<String> generated = new ArrayList<>();
        for (String apiPythonFile : apiPythonFiles) {
            Path apiPath = root.resolve(apiPythonFile).normalize();
            Path appDirectory = apiPath.getParent();
            Path stubPath = appDirectory.resolve("api.java.stub");
            if (!stubPath.startsWith(root)) {
                throw new IllegalArgumentException("Stub path escapes project root");
            }
            String appId = appId(relativeAppJson(apiPythonFile), metadata(root.resolve(relativeAppJson(apiPythonFile))));
            if (!Files.exists(stubPath)) {
                Files.writeString(stubPath, javaStubSource(appId, apiPythonFile));
            }
            generated.add(root.relativize(stubPath).toString().replace('\\', '/'));
        }
        return generated.stream().sorted().toList();
    }

    private String javaStubSource(String appId, String apiPythonFile) {
        String className = ProjectJavaNaming.className(appId) + "Api";
        return "import com.wiz.runtime.WizContext;\n"
                + "import com.wiz.runtime.WizResult;\n"
                + "import java.util.Map;\n\n"
                + "public final class " + className + " {\n"
                + "    // TODO: Port " + apiPythonFile + " to Java.\n"
                + "    public WizResult todo(WizContext wiz) {\n"
                + "        return wiz.response().status(501, Map.of(\"app\", \"" + escapeJava(appId) + "\", \"todo\", \"Port Python API to Java\"));\n"
                + "    }\n"
                + "}\n";
    }

    private List<ComponentMetadata> componentMetadata(Path root, List<String> appJsonFiles, List<String> apiPythonFiles) {
        return appJsonFiles.stream()
                .filter(this::isAppMetadata)
                .map(appJson -> componentMetadata(root, appJson, apiPythonFiles))
                .toList();
    }

    private ComponentMetadata componentMetadata(Path root, String appJson, List<String> apiPythonFiles) {
        Map<String, Object> metadata = metadata(root.resolve(appJson));
        String appId = appId(appJson, metadata);
        String appDirectory = parentPath(appJson);
        boolean hasPythonApi = apiPythonFiles.contains(appDirectory + "/api.py");
        String defaultJavaClass = "com.wiz.project.{project}.api." + ProjectJavaNaming.className(appId) + "Api";
        return new ComponentMetadata(
                appJson,
                appId,
                string(metadata, "controller"),
                string(metadata, "viewuri"),
                string(metadata, "layout"),
                string(metadata, "mode"),
                string(metadata, "template"),
                string(metadata, "route"),
                methods(metadata),
                defaultJavaClass,
                hasPythonApi ? appDirectory + "/api.java.stub" : "");
    }

    private List<RouteMetadata> routeMetadata(Path root, List<String> appJsonFiles) {
        return appJsonFiles.stream()
                .filter(this::isRouteMetadata)
                .map(appJson -> routeMetadata(root, appJson))
                .toList();
    }

    private RouteMetadata routeMetadata(Path root, String appJson) {
        Map<String, Object> metadata = metadata(root.resolve(appJson));
        String routeId = routeId(appJson, metadata);
        return new RouteMetadata(
                appJson,
                routeId,
                string(metadata, "route"),
                string(metadata, "controller"),
                methods(metadata),
                string(metadata, "handler", "com.wiz.project.{project}.route." + ProjectJavaNaming.className(routeId) + "RouteHandler"));
    }

    private List<PortalMetadata> portalMetadata(Path root, Path sourceRoot) throws IOException {
        return collectByFileName(root, sourceRoot.resolve("portal"), "portal.json").stream()
                .map(portalJson -> portalMetadata(root, portalJson))
                .toList();
    }

    private PortalMetadata portalMetadata(Path root, String portalJson) {
        Map<String, Object> metadata = metadata(root.resolve(portalJson));
        String portalName = portalName(portalJson);
        return new PortalMetadata(
                portalJson,
                string(metadata, "id", portalName),
                flag(metadata, "use_app", false),
                flag(metadata, "use_route", false),
                flag(metadata, "use_controller", false),
                flag(metadata, "use_model", false),
                flag(metadata, "use_assets", false),
                flag(metadata, "use_libs", false),
                flag(metadata, "use_styles", false));
    }

    private List<String> collectByFileName(Path root, Path base, String fileName) throws IOException {
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(base)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private List<String> collectUnder(Path root, Path base, String suffix) throws IOException {
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(base)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private List<String> collectAll(Path root, Path base) throws IOException {
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(base)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private Map<String, Object> metadata(Path appJson) {
        if (!Files.isRegularFile(appJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(Files.readAllBytes(appJson), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IOException | RuntimeException exception) {
            return Map.of();
        }
    }

    private boolean isAppMetadata(String appJson) {
        return appJson.startsWith("src/app/") || appJson.contains("/app/");
    }

    private boolean isRouteMetadata(String appJson) {
        return appJson.startsWith("src/route/") || appJson.contains("/route/");
    }

    private String appId(String appJson, Map<String, Object> metadata) {
        String configured = string(metadata, "id");
        if (!configured.isBlank()) {
            return configured;
        }
        List<String> parts = List.of(appJson.split("/"));
        int appIndex = parts.indexOf("app");
        if (appIndex > 1 && parts.get(0).equals("src") && parts.get(1).equals("portal") && parts.size() > appIndex + 1) {
            return "portal." + parts.get(2) + "." + parts.get(appIndex + 1);
        }
        if (appIndex >= 0 && parts.size() > appIndex + 1) {
            return parts.get(appIndex + 1);
        }
        return parentName(appJson);
    }

    private String routeId(String appJson, Map<String, Object> metadata) {
        String configured = string(metadata, "id");
        List<String> parts = List.of(appJson.split("/"));
        int routeIndex = parts.indexOf("route");
        String defaultId = routeIndex >= 0 && parts.size() > routeIndex + 1 ? parts.get(routeIndex + 1) : parentName(appJson);
        if (parts.size() > 2 && parts.get(0).equals("src") && parts.get(1).equals("portal") && !defaultId.startsWith("portal.")) {
            defaultId = "portal." + parts.get(2) + "." + defaultId;
        }
        return configured.isBlank() ? defaultId : configured;
    }

    private String relativeAppJson(String apiPythonFile) {
        return parentPath(apiPythonFile) + "/app.json";
    }

    private String parentPath(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? "" : path.substring(0, index);
    }

    private String parentName(String path) {
        String parent = parentPath(path);
        int index = parent.lastIndexOf('/');
        return index < 0 ? parent : parent.substring(index + 1);
    }

    private String portalName(String portalJson) {
        List<String> parts = List.of(portalJson.split("/"));
        return parts.size() > 2 ? parts.get(2) : parentName(portalJson);
    }

    private List<String> methods(Map<String, Object> metadata) {
        Object value = metadata.get("methods");
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).filter(item -> !item.isBlank()).toList();
        }
        if (value == null || value.toString().isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.toString().split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private boolean flag(Map<String, Object> metadata, String key, boolean defaultValue) {
        Object value = metadata.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? defaultValue : Boolean.parseBoolean(value.toString());
    }

    private String string(Map<String, Object> metadata, String key) {
        return string(metadata, key, "");
    }

    private String string(Map<String, Object> metadata, String key, String defaultValue) {
        Object value = metadata.get(key);
        return value == null || value.toString().isBlank() ? defaultValue : value.toString();
    }

    private String escapeJava(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record ProjectInventory(
            List<String> components,
            List<String> apiPythonFiles,
            List<String> socketPythonFiles,
            List<String> controllerPythonFiles,
            List<String> modelPythonFiles,
            List<String> routeFiles,
            List<String> portalComponents,
            List<ComponentMetadata> componentMetadata,
            List<RouteMetadata> routeMetadata,
            List<PortalMetadata> portalMetadata,
            List<String> generatedStubFiles) {

        private static final ObjectMapper JSON = new ObjectMapper();

        public static ProjectInventory empty() {
            return new ProjectInventory(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        public boolean hasPythonSource() {
            return !apiPythonFiles.isEmpty() || !socketPythonFiles.isEmpty() || !controllerPythonFiles.isEmpty() || !modelPythonFiles.isEmpty();
        }

        public ProjectInventory withGeneratedStubFiles(List<String> stubs) {
            return new ProjectInventory(components, apiPythonFiles, socketPythonFiles, controllerPythonFiles, modelPythonFiles, routeFiles, portalComponents, componentMetadata, routeMetadata, portalMetadata, stubs);
        }

        public String toJson() {
            try {
                return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(toMap()) + "\n";
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Failed to serialize migration report", exception);
            }
        }

        public String toMarkdown() {
            return "# WIZ Python Migration Report\n\n"
                    + "## Summary\n\n"
                    + "- Components: " + components.size() + "\n"
                    + "- App API Python files: " + apiPythonFiles.size() + "\n"
                    + "- Socket Python files: " + socketPythonFiles.size() + "\n"
                    + "- Controller Python files: " + controllerPythonFiles.size() + "\n"
                    + "- Model Python files: " + modelPythonFiles.size() + "\n"
                    + "- Route files: " + routeFiles.size() + "\n"
                    + "- Portal components: " + portalComponents.size() + "\n"
                    + "- Generated Java stubs: " + generatedStubFiles.size() + "\n\n"
                    + section("App API Python files", apiPythonFiles)
                    + section("Socket Python files", socketPythonFiles)
                    + section("Controller Python files", controllerPythonFiles)
                    + section("Model Python files", modelPythonFiles)
                    + section("Route files", routeFiles)
                    + section("Generated Java stubs", generatedStubFiles)
                    + componentSection()
                    + routeSection()
                    + portalSection()
                    + "## Porting Order\n\n"
                    + "1. Config and model/schema source\n"
                    + "2. Controller chain and auth guards\n"
                    + "3. App API handlers\n"
                    + "4. Route handlers\n"
                    + "5. Socket handlers\n"
                    + "6. Frontend build and assets\n\n"
                    + "## Next Steps\n\n"
                    + "- Port each `api.py` to component-local `api.java`.\n"
                    + "- Use generated `api.java.stub` files only as compile-disabled starting points.\n"
                    + "- Keep `app.json`, view files, styles, scripts, and Java API code together in the component directory.\n"
                    + "- Convert Python models and structs into Java classes before enabling project build.\n";
        }

        private Map<String, Object> toMap() {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            values.put("pythonSource", hasPythonSource());
            values.put("components", components);
            values.put("apiPythonFiles", apiPythonFiles);
            values.put("socketPythonFiles", socketPythonFiles);
            values.put("controllerPythonFiles", controllerPythonFiles);
            values.put("modelPythonFiles", modelPythonFiles);
            values.put("routeFiles", routeFiles);
            values.put("portalComponents", portalComponents);
            values.put("componentMetadata", componentMetadata.stream().map(ComponentMetadata::toMap).toList());
            values.put("routeMetadata", routeMetadata.stream().map(RouteMetadata::toMap).toList());
            values.put("portalMetadata", portalMetadata.stream().map(PortalMetadata::toMap).toList());
            values.put("generatedStubFiles", generatedStubFiles);
            values.put("portingOrder", List.of("config/model", "controller", "app api", "route", "socket", "frontend build"));
            values.put("nextSteps", List.of("Port api.py files to app-local api.java", "Port model Python files to Java model/struct classes", "Rename api.java.stub to api.java only after filling Java implementation"));
            return values;
        }

        private String componentSection() {
            if (componentMetadata.isEmpty()) {
                return "## Component Metadata\n\nNone.\n\n";
            }
            StringBuilder builder = new StringBuilder("## Component Metadata\n\n");
            for (ComponentMetadata metadata : componentMetadata) {
                builder.append("- `").append(metadata.path()).append("`: appId=").append(value(metadata.appId()))
                        .append(", controller=").append(value(metadata.controller()))
                        .append(", viewuri=").append(value(metadata.viewuri()))
                        .append(", mode=").append(value(metadata.mode()))
                        .append(", template=").append(value(metadata.template()))
                        .append(", defaultJavaClass=").append(value(metadata.defaultJavaClass()))
                        .append(", stubTarget=").append(value(metadata.stubTarget()))
                        .append("\n");
            }
            return builder.append('\n').toString();
        }

        private String routeSection() {
            if (routeMetadata.isEmpty()) {
                return "## Route Metadata\n\nNone.\n\n";
            }
            StringBuilder builder = new StringBuilder("## Route Metadata\n\n");
            for (RouteMetadata metadata : routeMetadata) {
                builder.append("- `").append(metadata.path()).append("`: id=").append(value(metadata.id()))
                        .append(", route=").append(value(metadata.route()))
                        .append(", controller=").append(value(metadata.controller()))
                        .append(", methods=").append(metadata.methods())
                        .append(", handler=").append(value(metadata.handler()))
                        .append("\n");
            }
            return builder.append('\n').toString();
        }

        private String portalSection() {
            if (portalMetadata.isEmpty()) {
                return "## Portal Metadata\n\nNone.\n\n";
            }
            StringBuilder builder = new StringBuilder("## Portal Metadata\n\n");
            for (PortalMetadata metadata : portalMetadata) {
                builder.append("- `").append(metadata.path()).append("`: id=").append(value(metadata.id()))
                        .append(", use_app=").append(metadata.useApp())
                        .append(", use_route=").append(metadata.useRoute())
                        .append(", use_controller=").append(metadata.useController())
                        .append(", use_model=").append(metadata.useModel())
                        .append(", use_assets=").append(metadata.useAssets())
                        .append(", use_libs=").append(metadata.useLibs())
                        .append(", use_styles=").append(metadata.useStyles())
                        .append("\n");
            }
            return builder.append('\n').toString();
        }

        private String section(String title, List<String> values) {
            if (values.isEmpty()) {
                return "## " + title + "\n\nNone.\n\n";
            }
            StringBuilder builder = new StringBuilder("## ").append(title).append("\n\n");
            for (String value : values) {
                builder.append("- `").append(value).append("`\n");
            }
            return builder.append('\n').toString();
        }

        private String value(String value) {
            return value == null || value.isBlank() ? "-" : value;
        }
    }

    public record ComponentMetadata(String path, String appId, String controller, String viewuri, String layout, String mode, String template, String route, List<String> methods, String defaultJavaClass, String stubTarget) {
        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            values.put("path", path);
            values.put("appId", appId);
            values.put("controller", controller);
            values.put("viewuri", viewuri);
            values.put("layout", layout);
            values.put("mode", mode);
            values.put("template", template);
            values.put("route", route);
            values.put("methods", methods);
            values.put("defaultJavaClass", defaultJavaClass);
            values.put("stubTarget", stubTarget);
            return values;
        }
    }

    public record RouteMetadata(String path, String id, String route, String controller, List<String> methods, String handler) {
        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            values.put("path", path);
            values.put("id", id);
            values.put("route", route);
            values.put("controller", controller);
            values.put("methods", methods);
            values.put("handler", handler);
            return values;
        }
    }

    public record PortalMetadata(String path, String id, boolean useApp, boolean useRoute, boolean useController, boolean useModel, boolean useAssets, boolean useLibs, boolean useStyles) {
        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            values.put("path", path);
            values.put("id", id);
            values.put("use_app", useApp);
            values.put("use_route", useRoute);
            values.put("use_controller", useController);
            values.put("use_model", useModel);
            values.put("use_assets", useAssets);
            values.put("use_libs", useLibs);
            values.put("use_styles", useStyles);
            return values;
        }
    }
}