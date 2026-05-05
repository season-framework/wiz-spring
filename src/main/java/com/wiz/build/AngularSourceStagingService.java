package com.wiz.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.wiz.core.ProjectJavaNaming;
import com.wiz.runtime.ProjectContext;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

final class AngularSourceStagingService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    void stage(ProjectContext project) throws IOException {
        Path buildSourceRoot = project.buildRoot().resolve("src");
        Path angularRoot = buildSourceRoot.resolve("angular");
        if (!Files.isRegularFile(angularRoot.resolve("package.json")) || !Files.isRegularFile(angularRoot.resolve("angular.json"))) {
            return;
        }

        Path angularSrc = angularRoot.resolve("src");
        Files.createDirectories(angularSrc);
        copyAngularShell(angularRoot, angularSrc);
        copyIfExists(buildSourceRoot.resolve("libs"), angularSrc.resolve("libs"));
        copyIfExists(buildSourceRoot.resolve("assets"), angularSrc.resolve("assets"));
        loosenTemplateFacingServiceTypes(angularSrc);
        writeTsConfig(angularRoot);
        writeTypeDeclarations(angularSrc);

        List<ComponentDefinition> components = generateComponents(buildSourceRoot.resolve("app"), angularSrc.resolve("app"));
        patchAppComponent(angularSrc.resolve("app/app.component.ts"));
        patchNgModule(angularSrc.resolve("app/app.module.ts"), components);
        patchRoutingModule(angularSrc.resolve("app/app-routing.module.ts"), components);
        patchAngularDecorators(angularSrc);
        disableTypeChecking(angularSrc.resolve("libs"));
    }

    private void copyAngularShell(Path angularRoot, Path angularSrc) throws IOException {
        copyIfExists(angularRoot.resolve("app"), angularSrc.resolve("app"));
        copyFileIfExists(angularRoot.resolve("main.ts"), angularSrc.resolve("main.ts"));
        copyFileIfExists(angularRoot.resolve("wiz.ts"), angularSrc.resolve("wiz.ts"));
        copyFileIfExists(angularRoot.resolve("index.html"), angularSrc.resolve("index.html"));
        copyFileIfExists(angularRoot.resolve("index.pug"), angularSrc.resolve("index.pug"));
        copyFileIfExists(angularRoot.resolve("styles/styles.scss"), angularSrc.resolve("styles.scss"));
        copyFileIfExists(angularRoot.resolve("tailwind.css"), angularRoot.resolve("tailwind.min.css"));
    }

    private List<ComponentDefinition> generateComponents(Path appRoot, Path angularAppRoot) throws IOException {
        if (!Files.isDirectory(appRoot)) {
            return List.of();
        }
        ArrayList<ComponentDefinition> components = new ArrayList<>();
        try (Stream<Path> apps = Files.list(appRoot)) {
            for (Path app : apps.filter(Files::isDirectory).sorted().toList()) {
                Path viewTs = app.resolve("view.ts");
                if (!Files.isRegularFile(viewTs)) {
                    continue;
                }
                Map<String, Object> metadata = readMetadata(app.resolve("app.json"));
                String appId = string(metadata, "id", app.getFileName().toString());
                ComponentDefinition component = componentDefinition(appId, metadata);
                Path target = angularAppRoot.resolve(appId);
                Files.createDirectories(target);
                copyFileIfExists(app.resolve("view.pug"), target.resolve("view.pug"));
                copyFileIfExists(app.resolve("view.html"), target.resolve("view.html"));
                if (Files.isRegularFile(app.resolve("view.scss"))) {
                    copyFileIfExists(app.resolve("view.scss"), target.resolve("view.scss"));
                } else {
                    Files.writeString(target.resolve("view.scss"), "");
                }
                if (!Files.isRegularFile(target.resolve("view.html")) && !Files.isRegularFile(target.resolve("view.pug"))) {
                    Files.writeString(target.resolve("view.html"), "");
                }
                Files.writeString(target.resolve(appId + ".component.ts"), componentSource(component, Files.readString(viewTs)));
                components.add(component);
            }
        }
        return List.copyOf(components);
    }

    private ComponentDefinition componentDefinition(String appId, Map<String, Object> metadata) {
        Map<String, Object> ng = map(metadata.get("ng"));
        Map<String, Object> ngBuild = map(metadata.get("ng.build"));
        String className = string(ngBuild, "name", ProjectJavaNaming.componentName(appId));
        String selector = string(ng, "selector", ProjectJavaNaming.selector(appId));
        String mode = string(metadata, "mode", "app");
        String route = string(metadata, "viewuri", "");
        String layout = string(metadata, "layout", "");
        return new ComponentDefinition(appId, className, selector, mode, route, layout);
    }

    private String componentSource(ComponentDefinition component, String originalSource) {
        String rewritten = originalSource.replaceFirst("export\\s+class\\s+Component\\b", "export class " + component.className());
        SplitSource split = splitLeadingImports(rewritten.stripLeading());
        return "// @ts-nocheck\n"
                + "import { Component } from '@angular/core';\n"
                + "import Wiz from '../../wiz';\n"
                + split.imports()
                + "declare const WizRoute: any;\n"
                + "let wiz = new Wiz('/wiz').app('" + escapeTs(component.appId()) + "');\n"
                + "@Component({\n"
                + "    selector: '" + escapeTs(component.selector()) + "',\n"
                + "    templateUrl: './view.html',\n"
                + "    styleUrls: ['./view.scss'],\n"
                + "    standalone: false\n"
                + "})\n"
                + split.body().stripLeading()
                + "\n\n"
                + "export default " + component.className() + ";\n";
    }

    private SplitSource splitLeadingImports(String source) {
        StringBuilder imports = new StringBuilder();
        StringBuilder body = new StringBuilder();
        boolean inImports = true;
        for (String line : source.split("\\R", -1)) {
            if (inImports && (line.startsWith("import ") || line.isBlank())) {
                imports.append(line).append("\n");
            } else {
                inImports = false;
                body.append(line).append("\n");
            }
        }
        if (!imports.isEmpty() && !imports.toString().endsWith("\n\n")) {
            imports.append("\n");
        }
        return new SplitSource(imports.toString(), body.toString());
    }

    private void patchAppComponent(Path appComponent) throws IOException {
        if (!Files.isRegularFile(appComponent)) {
            return;
        }
        String source = Files.readString(appComponent);
        if (!source.startsWith("// @ts-nocheck")) {
            source = "// @ts-nocheck\n" + source;
        }
        if (!source.contains("standalone:")) {
            source = source.replace("styleUrls: ['./app.component.scss']", "styleUrls: ['./app.component.scss'],\n    standalone: false");
        }
        Files.writeString(appComponent, source);
    }

    private void patchNgModule(Path appModule, List<ComponentDefinition> components) throws IOException {
        if (!Files.isRegularFile(appModule)) {
            return;
        }
        String source = Files.readString(appModule);
        String imports = components.stream()
                .map(component -> "import { " + component.className() + " } from './" + component.appId() + "/" + component.appId() + ".component';")
                .collect(Collectors.joining("\n"));
        source = imports + "\n" + source;
        source = source.replace("@Pipe({ name: 'safe' })", "@Pipe({ name: 'safe', standalone: false })");
        source = source.replace("'@wiz.declarations'", declarationList(components));
        source = source.replace("'@wiz.imports'", "");
        Files.writeString(appModule, source);
    }

    private String declarationList(List<ComponentDefinition> components) {
        ArrayList<String> declarations = new ArrayList<>();
        declarations.add("AppComponent");
        components.stream().map(ComponentDefinition::className).forEach(declarations::add);
        return declarations.stream().collect(Collectors.joining(",\n        "));
    }

    private void patchRoutingModule(Path routingModule, List<ComponentDefinition> components) throws IOException {
        if (!Files.isRegularFile(routingModule)) {
            return;
        }
        String source = Files.readString(routingModule);
        String imports = components.stream()
                .map(component -> "import { " + component.className() + " } from './" + component.appId() + "/" + component.appId() + ".component';")
                .collect(Collectors.joining("\n"));
        source = imports + "\n" + source;
        source = source.replaceFirst("const INDEX_PAGE = \\\"[^\\\"]*\\\";", "const INDEX_PAGE = \"" + escapeTs(indexPage(components)) + "\";");
        source = source.replace("let app_routes: Routes = wiz.routes();", "let app_routes: Routes = " + routesLiteral(components) + ";");
        source = source.replace("window.WizRoute", "(window as any).WizRoute");
        Files.writeString(routingModule, source);
    }

    private String indexPage(List<ComponentDefinition> components) {
        return components.stream()
                .filter(component -> component.mode().equals("page"))
                .map(component -> routePath(component.route()))
                .filter(route -> !route.isBlank())
                .findFirst()
                .orElse("");
    }

    private String routesLiteral(List<ComponentDefinition> components) {
        Map<String, ComponentDefinition> byId = components.stream().collect(Collectors.toMap(ComponentDefinition::appId, component -> component, (left, right) -> left, LinkedHashMap::new));
        Map<String, List<ComponentDefinition>> pagesByLayout = components.stream()
                .filter(component -> component.mode().equals("page"))
                .filter(component -> !routePath(component.route()).isBlank())
                .collect(Collectors.groupingBy(component -> component.layout().isBlank() ? "layout.empty" : component.layout(), LinkedHashMap::new, Collectors.toList()));

        StringBuilder builder = new StringBuilder("[\n");
        boolean firstLayout = true;
        for (Map.Entry<String, List<ComponentDefinition>> entry : pagesByLayout.entrySet()) {
            ComponentDefinition layout = byId.get(entry.getKey());
            if (layout == null) {
                continue;
            }
            if (!firstLayout) {
                builder.append(",\n");
            }
            firstLayout = false;
            builder.append("    {\n")
                    .append("        component: ").append(layout.className()).append(",\n")
                    .append("        children: [\n");
            for (int index = 0; index < entry.getValue().size(); index++) {
                ComponentDefinition page = entry.getValue().get(index);
                if (index > 0) {
                    builder.append(",\n");
                }
                builder.append("            { path: \"").append(escapeTs(routePath(page.route()))).append("\", component: ")
                        .append(page.className()).append(", data: { app_id: \"").append(escapeTs(page.appId())).append("\" } }");
            }
            builder.append("\n        ]\n    }");
        }
        return builder.append("\n]").toString();
    }

    private String routePath(String route) {
        String value = route == null ? "" : route.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    private void patchAngularDecorators(Path angularSrc) throws IOException {
        if (!Files.isDirectory(angularSrc)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(angularSrc)) {
            for (Path sourceFile : paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".ts")).toList()) {
                String source = Files.readString(sourceFile);
                String patched = source;
                patched = patched.replaceAll("@Directive\\(\\{(?![^}]*standalone)\\s*selector:", "@Directive({\n    standalone: false,\n    selector:");
                patched = patched.replaceAll("@Pipe\\(\\{(?![^}]*standalone)\\s*name:", "@Pipe({\n    standalone: false,\n    name:");
                if (!source.equals(patched)) {
                    Files.writeString(sourceFile, patched);
                }
            }
        }
    }

    private void loosenTemplateFacingServiceTypes(Path angularSrc) throws IOException {
        Path service = angularSrc.resolve("libs/portal/season/service.ts");
        if (!Files.isRegularFile(service)) {
            return;
        }
        String source = Files.readString(service);
        String patched = source.replace("public status: Status;", "public status: any;");
        if (!source.equals(patched)) {
            Files.writeString(service, patched);
        }
    }

    private void writeTsConfig(Path angularRoot) throws IOException {
        Files.writeString(angularRoot.resolve("tsconfig.json"), "{\n"
                + "  \"compileOnSave\": false,\n"
                + "  \"compilerOptions\": {\n"
                + "    \"baseUrl\": \"./\",\n"
                + "    \"outDir\": \"./dist/out-tsc\",\n"
                + "    \"forceConsistentCasingInFileNames\": true,\n"
                + "    \"strict\": false,\n"
                + "    \"noImplicitAny\": false,\n"
                + "    \"noImplicitReturns\": false,\n"
                + "    \"noFallthroughCasesInSwitch\": false,\n"
                + "    \"skipLibCheck\": true,\n"
                + "    \"sourceMap\": true,\n"
                + "    \"declaration\": false,\n"
                + "    \"downlevelIteration\": true,\n"
                + "    \"experimentalDecorators\": true,\n"
                + "    \"moduleResolution\": \"bundler\",\n"
                + "    \"importHelpers\": true,\n"
                + "    \"target\": \"ES2022\",\n"
                + "    \"module\": \"ES2022\",\n"
                + "    \"useDefineForClassFields\": false,\n"
                + "    \"lib\": [\"ES2022\", \"dom\"],\n"
                + "    \"paths\": {\n"
                + "      \"@wiz/libs/portal/season/ngx-sortablejs\": [\"src/libs/portal/season/ngx-sortablejs/src/public-api.ts\"],\n"
                + "      \"@wiz/libs/*\": [\"src/libs/*\"],\n"
                + "      \"@wiz/*\": [\"src/*\"],\n"
                + "      \"src/*\": [\"src/*\"]\n"
                + "    }\n"
                + "  },\n"
                + "  \"angularCompilerOptions\": {\n"
                + "    \"strictInjectionParameters\": false,\n"
                + "    \"strictInputAccessModifiers\": false,\n"
                + "    \"strictTemplates\": false\n"
                + "  }\n"
                + "}\n");
        Files.writeString(angularRoot.resolve("tsconfig.app.json"), "{\n"
                + "  \"extends\": \"./tsconfig.json\",\n"
                + "  \"compilerOptions\": {\n"
                + "    \"outDir\": \"./out-tsc/app\",\n"
                + "    \"types\": []\n"
                + "  },\n"
                + "  \"files\": [\"src/main.ts\"],\n"
                + "  \"include\": [\"src/**/*.d.ts\"]\n"
                + "}\n");
    }

    private void writeTypeDeclarations(Path angularSrc) throws IOException {
        Files.writeString(angularSrc.resolve("types.d.ts"), "declare global {\n"
                + "  interface Window { WizRoute?: any; MonacoEnvironment?: any; }\n"
                + "  interface Navigator { userLanguage?: string; }\n"
                + "}\n"
                + "declare const WizRoute: any;\n"
                + "export {};\n");
    }

    private void disableTypeChecking(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path sourceFile : paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".ts")).toList()) {
                String source = Files.readString(sourceFile);
                if (!source.startsWith("// @ts-nocheck")) {
                    Files.writeString(sourceFile, "// @ts-nocheck\n" + source);
                }
            }
        }
    }

    private Map<String, Object> readMetadata(Path metadataFile) {
        if (!Files.isRegularFile(metadataFile)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(Files.readAllBytes(metadataFile), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IOException | RuntimeException exception) {
            return Map.of();
        }
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, nestedValue) -> result.put(String.valueOf(key), nestedValue));
        return result;
    }

    private String string(Map<String, Object> metadata, String key, String defaultValue) {
        Object value = metadata.get(key);
        return value == null || value.toString().isBlank() ? defaultValue : value.toString();
    }

    private String escapeTs(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'");
    }

    private void copyIfExists(Path source, Path target) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        copyDirectory(source, target);
    }

    private void copyFileIfExists(Path source, Path target) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(source)) {
            throw new IllegalArgumentException("Symbolic links are not allowed in Angular source staging: " + source.getFileName());
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        delete(target);
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path item : paths.toList()) {
                Path relative = source.relativize(item);
                Path destination = target.resolve(relative.toString()).normalize();
                if (!destination.startsWith(target.normalize())) {
                    throw new IllegalArgumentException("Angular source staging copy escapes target directory");
                }
                if (Files.isSymbolicLink(item)) {
                    throw new IllegalArgumentException("Symbolic links are not allowed in Angular source staging: " + relative);
                }
                if (Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(item, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void delete(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    private record ComponentDefinition(String appId, String className, String selector, String mode, String route, String layout) {
    }

    private record SplitSource(String imports, String body) {
    }
}
