package com.wiz.build;

import java.io.IOException;
import java.io.File;
import java.io.StringWriter;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.wiz.core.ProjectJavaNaming;
import com.wiz.runtime.BuildMarkerService;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.SafePath;

import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProjectBuildService {

    private final ConcurrentHashMap<Path, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AngularBuildService angularBuildService;
    private final BuildMarkerService buildMarkerService = new BuildMarkerService();

    public ProjectBuildService() {
        this(new AngularBuildService());
    }

    ProjectBuildService(AngularBuildService angularBuildService) {
        this.angularBuildService = angularBuildService;
    }

    public BuildResult build(ProjectContext project, boolean clean, String phase) throws IOException {
        return build(project, clean, phase, BuildLogger.quiet());
    }

    public BuildResult build(ProjectContext project, boolean clean, String phase, BuildLogger logger) throws IOException {
        BuildLogger buildLogger = logger == null ? BuildLogger.quiet() : logger;
        String requestedPhase = phase == null || phase.isBlank() ? "bundle" : phase;
        if (!List.of("reconstruct", "compile", "bundle").contains(requestedPhase)) {
            return new BuildResult(2, List.of(requestedPhase), "Supported build phases: reconstruct, compile, bundle");
        }

        ReentrantLock lock = locks.computeIfAbsent(project.root(), ignored -> new ReentrantLock());
        lock.lock();
        Instant startedAt = Instant.now();
        long totalStarted = System.nanoTime();
        buildLogger.info("== WIZ project build ==");
        buildLogger.info("Project: " + project.name());
        buildLogger.info("Root: " + project.root());
        buildLogger.info("Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        buildLogger.info("Java home: " + System.getProperty("java.home"));
        buildLogger.info("Clean: " + clean);
        buildLogger.info("Phase: " + requestedPhase);
        try {
            if (clean) {
                timed(buildLogger, "clean", () -> {
                    delete(project.buildRoot());
                    delete(project.bundleRoot());
                    return null;
                });
            }
            timed(buildLogger, "reconstruct", () -> {
                reconstruct(project);
                return null;
            });
            if (requestedPhase.equals("reconstruct")) {
                return finish(buildLogger, totalStarted, new BuildResult(0, List.of("reconstruct"), "Reconstructed project source tree"));
            }

            timed(buildLogger, "java-source", () -> {
                reconstructProjectJava(project);
                return null;
            });
            BuildResult compile = timed(buildLogger, "java-compile", () -> compileProjectJava(project, buildLogger));
            if (!compile.success() || requestedPhase.equals("compile")) {
                return finish(buildLogger, totalStarted, compile);
            }

            FrontendBuildResult frontend = timed(buildLogger, "frontend", () -> angularBuildService.build(project, buildLogger));
            if (!frontend.success()) {
                return finish(buildLogger, totalStarted, new BuildResult(1, List.of("reconstruct", "java-compile", frontend.phase()), frontend.message()));
            }
            timed(buildLogger, "bundle", () -> {
                bundle(project);
                return null;
            });
            if (!frontend.built()) {
                timed(buildLogger, "frontend-fallback", () -> {
                    writeMinimalWebBundle(project);
                    return null;
                });
            }
            List<String> phases = List.of("reconstruct", "java-compile", frontend.phase(), "bundle");
            buildMarkerService.write(project, phases, frontend.built() ? "real" : "fallback", startedAt, Instant.now());
            return finish(buildLogger, totalStarted, new BuildResult(0, phases, "Generated Java WIZ bundle"));
        } finally {
            lock.unlock();
        }
    }

    public void reconstruct(ProjectContext project) throws IOException {
        SafePath root = new SafePath(project.root());
        Path buildSourceRoot = root.resolveForWrite("build/src");
        Files.createDirectories(buildSourceRoot);
        copyIfExists(project.sourceRoot(), buildSourceRoot);
        flattenPortals(project.sourceRoot().resolve("portal"), buildSourceRoot);
        new AppMetadataNormalizer(objectMapper).normalize(project, buildSourceRoot);
    }

    private <T> T timed(BuildLogger logger, String phase, BuildStep<T> step) throws IOException {
        long started = System.nanoTime();
        logger.info("[" + phase + "] start");
        try {
            T result = step.run();
            logger.info("[" + phase + "] done in " + formatDuration(elapsedMillis(started)));
            return result;
        } catch (IOException | RuntimeException exception) {
            logger.info("[" + phase + "] failed in " + formatDuration(elapsedMillis(started)) + ": " + exception.getMessage());
            throw exception;
        }
    }

    private BuildResult finish(BuildLogger logger, long totalStarted, BuildResult result) {
        logger.info("Build result: exitCode=" + result.exitCode() + " phases=" + String.join(",", result.phases()));
        logger.info("Total build time: " + formatDuration(elapsedMillis(totalStarted)));
        return result;
    }

    private long elapsedMillis(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private String formatDuration(long millis) {
        if (millis < 1000) {
            return millis + "ms";
        }
        return String.format(java.util.Locale.ROOT, "%.2fs", millis / 1000.0);
    }

    @FunctionalInterface
    private interface BuildStep<T> {
        T run() throws IOException;
    }

    private void reconstructProjectJava(ProjectContext project) throws IOException {
        Path appRoot = project.buildRoot().resolve("src/app");
        if (Files.isDirectory(appRoot)) {
            try (Stream<Path> apps = Files.list(appRoot)) {
                for (Path app : apps.filter(Files::isDirectory).toList()) {
                    String appId = app.getFileName().toString();
                    Path appJson = app.resolve("app.json");
                    Path apiSource = app.resolve("api.java");
                    if (Files.isRegularFile(apiSource)) {
                        writeJavaSource(project, handlerClass(project, appId, appJson), apiSource);
                    }
                    Path socketSource = app.resolve("socket.java");
                    if (Files.isRegularFile(socketSource)) {
                        writeJavaSource(project, socketHandlerClass(project, appId, appJson), socketSource);
                    }
                }
            }
        }

        reconstructModelJava(project);
        reconstructRouteJava(project);

        Path controllerRoot = project.buildRoot().resolve("src/controller");
        reconstructJavaTree(project, controllerRoot, ProjectJavaNaming.packageRoot(project.name()) + ".controller");
    }

    private void reconstructRouteJava(ProjectContext project) throws IOException {
        Path routeRoot = project.buildRoot().resolve("src/route");
        if (!Files.isDirectory(routeRoot)) {
            return;
        }
        try (Stream<Path> routes = Files.list(routeRoot)) {
            for (Path route : routes.filter(Files::isDirectory).toList()) {
                Path routeSource = route.resolve("route.java");
                if (!Files.isRegularFile(routeSource)) {
                    continue;
                }
                String routeId = route.getFileName().toString();
                writeJavaSource(project, routeHandlerClass(project, routeId, route.resolve("app.json")), routeSource);
            }
        }
    }

    private void reconstructModelJava(ProjectContext project) throws IOException {
        Path modelRoot = project.buildRoot().resolve("src/model");
        if (!Files.isDirectory(modelRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(modelRoot)) {
            for (Path source : paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java")).toList()) {
                Path relative = modelRoot.relativize(source);
                String handlerClass = modelHandlerClass(project, relative);
                writeJavaSource(project, handlerClass, source);
            }
        }
    }

    private String modelHandlerClass(ProjectContext project, Path relative) {
        String relativeName = relative.toString().substring(0, relative.toString().length() - ".java".length()).replace('\\', '/');
        String[] parts = relativeName.split("/");
        if (parts.length >= 3 && parts[0].equals("portal")) {
            String nested = java.util.Arrays.stream(parts, 2, parts.length)
                    .collect(java.util.stream.Collectors.joining("."));
            return ProjectJavaNaming.packageRoot(project.name()) + ".portal." + ProjectJavaNaming.packageSegment(parts[1]) + ".model." + nested;
        }
        return ProjectJavaNaming.packageRoot(project.name()) + ".model." + relativeName.replace('/', '.');
    }

    private void reconstructJavaTree(ProjectContext project, Path sourceRoot, String packagePrefix) throws IOException {
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            for (Path source : paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java")).toList()) {
                Path relative = sourceRoot.relativize(source);
                String relativeClass = relative.toString().substring(0, relative.toString().length() - ".java".length()).replace('/', '.').replace('\\', '.');
                writeJavaSource(project, packagePrefix + "." + relativeClass, source);
            }
        }
    }

    private void writeJavaSource(ProjectContext project, String handlerClass, Path source) throws IOException {
        Path target = project.buildRoot().resolve("main/java").resolve(handlerClass.replace('.', '/') + ".java");
        Files.createDirectories(target.getParent());
        Files.writeString(target, javaSource(handlerClass, Files.readString(source)));
    }

    private BuildResult compileProjectJava(ProjectContext project) throws IOException {
        return compileProjectJava(project, BuildLogger.quiet());
    }

    private BuildResult compileProjectJava(ProjectContext project, BuildLogger logger) throws IOException {
        Path sourceRoot = project.buildRoot().resolve("main/java");
        if (!Files.isDirectory(sourceRoot)) {
            logger.info("[java-compile] No Java project sources");
            return new BuildResult(0, List.of("reconstruct", "java-compile"), "No Java project sources");
        }

        List<Path> sources;
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            sources = paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java")).toList();
        }
        if (sources.isEmpty()) {
            logger.info("[java-compile] No Java project sources");
            return new BuildResult(0, List.of("reconstruct", "java-compile"), "No Java project sources");
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new BuildResult(1, List.of("reconstruct", "java-compile"), "JDK compiler is required to build Java project APIs");
        }

        Path classesRoot = project.buildRoot().resolve("classes");
        delete(classesRoot);
        Files.createDirectories(classesRoot);
        logger.info("[java-compile] source files: " + sources.size());
        logger.info("[java-compile] output: " + classesRoot);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StringWriter output = new StringWriter();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(sources);
            List<String> options = List.of(
                    "--release", "21",
                    "-classpath", compilerClasspath(project),
                    "-d", classesRoot.toString());
            logger.info("[java-compile] javac --release 21 -d " + classesRoot + " (" + sources.size() + " sources)");
            boolean success = Boolean.TRUE.equals(compiler.getTask(output, fileManager, diagnostics, options, null, units).call());
            String diagnosticText = diagnostics.getDiagnostics().stream()
                    .map(diagnostic -> diagnostic.getKind() + " " + diagnostic.getSource() + ":" + diagnostic.getLineNumber() + " " + diagnostic.getMessage(null))
                    .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
            String text = (output + System.lineSeparator() + diagnosticText).trim();
            if (!text.isBlank()) {
                logger.output(text + System.lineSeparator());
            }
            if (!success) {
                return new BuildResult(1, List.of("reconstruct", "java-compile"), text);
            }
        }

        packageProjectJar(project, classesRoot);
        logger.info("[java-compile] packaged " + project.buildRoot().resolve("project-api.jar"));
        return new BuildResult(0, List.of("reconstruct", "java-compile"), "Compiled Java project APIs");
    }

    private String compilerClasspath(ProjectContext project) throws IOException {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        String classpath = System.getProperty("java.class.path", "");
        if (!classpath.isBlank()) {
            for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) {
                    entries.add(entry);
                    Path path = Path.of(entry);
                    if (Files.isRegularFile(path) && isBootJar(path)) {
                        entries.addAll(extractBootJarClasspath(project, path));
                    }
                }
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private boolean isBootJar(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return jar.getEntry("BOOT-INF/classes/") != null || jar.getEntry("BOOT-INF/classpath.idx") != null;
        } catch (IOException exception) {
            return false;
        }
    }

    private List<String> extractBootJarClasspath(ProjectContext project, Path jarPath) throws IOException {
        Path classpathRoot = project.buildRoot().resolve("compiler-classpath");
        Path classes = classpathRoot.resolve("classes");
        Path libs = classpathRoot.resolve("lib");
        delete(classpathRoot);
        Files.createDirectories(classes);
        Files.createDirectories(libs);

        ArrayList<String> entries = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (JarEntry entry : java.util.Collections.list(jar.entries())) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                if (name.startsWith("BOOT-INF/classes/")) {
                    Path target = classes.resolve(name.substring("BOOT-INF/classes/".length())).normalize();
                    if (!target.startsWith(classes)) {
                        throw new IllegalArgumentException("Boot jar classpath extraction escapes target directory");
                    }
                    Files.createDirectories(target.getParent());
                    try (var input = jar.getInputStream(entry)) {
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } else if (name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar")) {
                    Path target = libs.resolve(Path.of(name).getFileName().toString()).normalize();
                    if (!target.startsWith(libs)) {
                        throw new IllegalArgumentException("Boot jar dependency extraction escapes target directory");
                    }
                    try (var input = jar.getInputStream(entry)) {
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    entries.add(target.toString());
                }
            }
        }
        entries.add(0, classes.toString());
        return entries;
    }

    public void bundle(ProjectContext project) throws IOException {
        delete(project.bundleRoot());
        Files.createDirectories(project.bundleRoot());
        copyIfExists(project.buildRoot().resolve("dist/build"), project.bundleWwwRoot());
        copyIfExists(project.buildRoot().resolve("src/assets"), project.bundleAssetsRoot());
        copyIfExists(project.buildRoot().resolve("src/controller"), project.bundleRoot().resolve("src/controller"));
        copyIfExists(project.buildRoot().resolve("src/model"), project.bundleRoot().resolve("src/model"));
        copyIfExists(project.buildRoot().resolve("src/route"), project.bundleRoot().resolve("src/route"));
        copyIfExists(project.configRoot(), project.bundleRoot().resolve("config"));
        copyIfExists(project.buildRoot().resolve("src/app"), project.bundleRoot().resolve("src/app"));
        copyIfExists(project.buildRoot().resolve("classes"), project.bundleRoot().resolve("classes"));
        copyFileIfExists(project.buildRoot().resolve("project-api.jar"), project.bundleRoot().resolve("project-api.jar"));
    }

    private String handlerClass(ProjectContext project, String appId, Path appJson) throws IOException {
        if (Files.isRegularFile(appJson)) {
            Map<String, Object> metadata = objectMapper.readValue(Files.readAllBytes(appJson), new TypeReference<>() {
            });
            Optional<String> configured = javaHandlerClass(metadata);
            if (configured.isPresent()) {
                return configured.get();
            }
        }
        return ProjectJavaNaming.appApiHandlerClass(project.name(), appId);
    }

    private Optional<String> javaHandlerClass(Map<String, Object> metadata) {
        Object api = metadata.get("api");
        if (!(api instanceof Map<?, ?> apiMap)) {
            return Optional.empty();
        }
        Object handler = apiMap.get("handler");
        if (handler == null || handler.toString().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(handler.toString());
    }

    private String socketHandlerClass(ProjectContext project, String appId, Path appJson) throws IOException {
        if (Files.isRegularFile(appJson)) {
            Map<String, Object> metadata = objectMapper.readValue(Files.readAllBytes(appJson), new TypeReference<>() {
            });
            Optional<String> configured = javaSocketHandlerClass(metadata);
            if (configured.isPresent()) {
                return configured.get();
            }
        }
        return ProjectJavaNaming.appSocketHandlerClass(project.name(), appId);
    }

    private Optional<String> javaSocketHandlerClass(Map<String, Object> metadata) {
        Object socket = metadata.get("socket");
        if (!(socket instanceof Map<?, ?> socketMap)) {
            return Optional.empty();
        }
        Object handler = socketMap.get("handler");
        if (handler == null || handler.toString().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(handler.toString());
    }

    private String routeHandlerClass(ProjectContext project, String routeId, Path appJson) throws IOException {
        if (Files.isRegularFile(appJson)) {
            Map<String, Object> metadata = objectMapper.readValue(Files.readAllBytes(appJson), new TypeReference<>() {
            });
            Object handler = metadata.get("handler");
            if (handler != null && !handler.toString().isBlank()) {
                return handler.toString();
            }
        }
        return ProjectJavaNaming.routeHandlerClass(project.name(), routeId);
    }

    private String javaSource(String handlerClass, String source) {
        if (source.stripLeading().startsWith("package ")) {
            return source;
        }
        int classStart = handlerClass.lastIndexOf('.');
        return "package " + handlerClass.substring(0, classStart) + ";\n\n" + source;
    }

    private void packageProjectJar(ProjectContext project, Path classesRoot) throws IOException {
        Path jar = project.buildRoot().resolve("project-api.jar");
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
                Stream<Path> paths = Files.walk(classesRoot)) {
            for (Path item : paths.filter(Files::isRegularFile).toList()) {
                String entryName = classesRoot.relativize(item).toString().replace('\\', '/');
                output.putNextEntry(new JarEntry(entryName));
                Files.copy(item, output);
                output.closeEntry();
            }
        }
    }

    private void writeMinimalWebBundle(ProjectContext project) throws IOException {
        Files.createDirectories(project.bundleWwwRoot());
        Path index = project.bundleWwwRoot().resolve("index.html");
        if (!Files.exists(index)) {
            Files.writeString(index, javaIndex(project));
        }
        Path script = project.bundleWwwRoot().resolve("app.js");
        if (!Files.exists(script)) {
            Files.writeString(script, javaApiScript(project));
        }
    }

    private String javaIndex(ProjectContext project) throws IOException {
        Path viewHtml = entryAppFile(project, "view.html");
        String body = Files.exists(viewHtml) ? Files.readString(viewHtml) : "<main id=\"wiz-app\">WIZ Java</main>";
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>WIZ Java</title></head><body>"
                + body
                + "<script type=\"module\" src=\"/app.js\"></script></body></html>";
    }

    private String javaApiScript(ProjectContext project) throws IOException {
        Path viewScript = entryAppFile(project, "view.ts");
        if (Files.exists(viewScript)) {
            return Files.readString(viewScript);
        }
        return "const status = document.querySelector('[data-wiz-status]');\n"
                + "fetch('/wiz/api/page.dashboard/overview', { method: 'POST' })\n"
                + "  .then((response) => response.json())\n"
                + "  .then((payload) => { if (status) status.textContent = `API ${payload.code}`; });\n";
    }

    private Path entryAppFile(ProjectContext project, String filename) {
        Path appRoot = project.buildRoot().resolve("src/app");
        for (String appId : List.of("page.access", "page.dashboard", "page.main")) {
            Path candidate = appRoot.resolve(appId).resolve(filename);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        if (Files.isDirectory(appRoot)) {
            try (Stream<Path> apps = Files.list(appRoot)) {
                Optional<Path> candidate = apps
                        .filter(Files::isDirectory)
                        .map(app -> app.resolve(filename))
                        .filter(Files::exists)
                        .findFirst();
                if (candidate.isPresent()) {
                    return candidate.get();
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to resolve entry app", exception);
            }
        }
        return appRoot.resolve("page.dashboard").resolve(filename);
    }

    private void flattenPortals(Path portalRoot, Path buildSourceRoot) throws IOException {
        if (!Files.isDirectory(portalRoot)) {
            return;
        }
        try (Stream<Path> modules = Files.list(portalRoot)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                PortalMetadata metadata = PortalMetadata.read(module, objectMapper);
                if (metadata.useApp()) {
                    flattenPortalApps(module, "app", buildSourceRoot.resolve("app"));
                }
                if (metadata.useWidget()) {
                    flattenPortalApps(module, "widget", buildSourceRoot.resolve("app"));
                }
                if (metadata.useRoute()) {
                    flattenPortalRoutes(module, buildSourceRoot.resolve("route"));
                }
                if (metadata.useController()) {
                    copyPortalFiles(module, "controller", buildSourceRoot.resolve("controller"));
                }
                if (metadata.useModel()) {
                    copyPortalFiles(module, "model", buildSourceRoot.resolve("model"));
                }
                if (metadata.useAssets()) {
                    copyPortalFiles(module, "assets", buildSourceRoot.resolve("assets"));
                }
                if (metadata.useLibs()) {
                    copyPortalFiles(module, "libs", buildSourceRoot.resolve("libs"));
                }
                if (metadata.useStyles()) {
                    copyPortalFiles(module, "styles", buildSourceRoot.resolve("styles"));
                }
            }
        }
    }

    private void flattenPortalApps(Path module, String kind, Path targetAppRoot) throws IOException {
        Path appRoot = module.resolve(kind);
        if (!Files.isDirectory(appRoot)) {
            return;
        }
        try (Stream<Path> apps = Files.list(appRoot)) {
            for (Path app : apps.filter(Files::isDirectory).toList()) {
                String appId = "portal." + module.getFileName() + "." + app.getFileName();
                copyDirectory(app, targetAppRoot.resolve(appId));
            }
        }
    }

    private void flattenPortalRoutes(Path module, Path targetRouteRoot) throws IOException {
        Path routeRoot = module.resolve("route");
        if (!Files.isDirectory(routeRoot)) {
            return;
        }
        try (Stream<Path> routes = Files.list(routeRoot)) {
            for (Path route : routes.filter(Files::isDirectory).toList()) {
                String routeId = "portal." + module.getFileName() + "." + route.getFileName();
                copyDirectory(route, targetRouteRoot.resolve(routeId));
            }
        }
    }

    private void copyPortalFiles(Path module, String kind, Path targetRoot) throws IOException {
        Path source = module.resolve(kind);
        if (!Files.isDirectory(source)) {
            return;
        }
        copyDirectory(source, targetRoot.resolve("portal").resolve(module.getFileName().toString()));
    }

    private void copyIfExists(Path source, Path target) throws IOException {
        if (Files.exists(source)) {
            copyDirectory(source, target);
        }
    }

    private void copyFileIfExists(Path source, Path target) throws IOException {
        if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(source)) {
                throw new IllegalArgumentException("Symbolic links are not allowed in build copies: " + source.getFileName());
            }
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path item : paths.toList()) {
                Path relative = source.relativize(item);
                Path destination = target.resolve(relative.toString()).normalize();
                if (!destination.startsWith(target.normalize())) {
                    throw new IllegalArgumentException("Build copy escapes target directory");
                }
                if (Files.isSymbolicLink(item)) {
                    throw new IllegalArgumentException("Symbolic links are not allowed in build copies: " + relative);
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
}
