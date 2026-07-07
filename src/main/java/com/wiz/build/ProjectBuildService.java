package com.wiz.build;

import java.io.IOException;
import java.io.File;
import java.io.StringWriter;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
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
import com.wiz.runtime.ProjectClassPath;
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
        buildLogger.info("== WIZ app build ==");
        buildLogger.info("Workspace: " + project.root());
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
                reconstruct(project, !clean);
                return null;
            });
            if (requestedPhase.equals("reconstruct")) {
                return finish(buildLogger, totalStarted, new BuildResult(0, List.of("reconstruct"), "Reconstructed app source tree"));
            }

            timed(buildLogger, "java-source", () -> {
                reconstructProjectJava(project);
                return null;
            });
            timed(buildLogger, "app-dependencies", () -> {
                resolveProjectDependencies(project, buildLogger);
                return null;
            });
            BuildResult compile = timed(buildLogger, "java-compile", () -> compileProjectJava(project, buildLogger));
            if (!compile.success() || requestedPhase.equals("compile")) {
                return finish(buildLogger, totalStarted, compile);
            }

            FrontendBuildResult frontend = timed(buildLogger, "frontend", () -> angularBuildService.build(project, clean, buildLogger));
            if (!frontend.success()) {
                return finish(buildLogger, totalStarted, new BuildResult(1, List.of("reconstruct", "java-source", "app-dependencies", "java-compile", frontend.phase()), frontend.message()));
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
            List<String> phases = List.of("reconstruct", "java-source", "app-dependencies", "java-compile", frontend.phase(), "bundle");
            SupplyChainManifestService.Result supplyChain = timed(buildLogger, "supply-chain", () -> new SupplyChainManifestService().write(project, Instant.now()));
            buildMarkerService.write(project, phases, frontend.built() ? "real" : "fallback", startedAt, Instant.now(),
                    new BuildMarkerService.DependencySummary(
                            "bundle/" + SupplyChainManifestService.DEPENDENCY_MANIFEST_FILE,
                            supplyChain.digestAlgorithm(),
                            supplyChain.dependencyDigest(),
                            supplyChain.dependencyCount(),
                            project.buildRoot().relativize(ProjectBuildLayout.cyclonedxBom(project)).toString().replace('\\', '/')));
            return finish(buildLogger, totalStarted, new BuildResult(0, phases, "Generated Java WIZ app bundle"));
        } finally {
            lock.unlock();
        }
    }

    public void reconstruct(ProjectContext project) throws IOException {
        reconstruct(project, false);
    }

    private void reconstruct(ProjectContext project, boolean preserveFrontendDependencies) throws IOException {
        Files.createDirectories(project.buildRoot());
        deleteLegacyBuildArtifacts(project);
        SafePath root = new SafePath(project.buildRoot());
        Path buildSourceRoot = root.resolveForWrite(".wiz/source");
        if (preserveFrontendDependencies) {
            deleteBuildSourceRootExceptFrontendDependencies(buildSourceRoot);
        } else {
            delete(buildSourceRoot);
        }
        Files.createDirectories(buildSourceRoot);
        copyIfExists(project.sourceRoot(), buildSourceRoot);
        flattenPortals(project.sourceRoot().resolve("portal"), buildSourceRoot);
        new AppMetadataNormalizer(objectMapper).normalize(project, buildSourceRoot);
    }

    private void deleteLegacyBuildArtifacts(ProjectContext project) throws IOException {
        for (String path : List.of(
                "main",
                "classes",
                "app-api.jar",
                "dist",
                "compiler-classpath",
                "src/angular",
                "src/app",
                "src/assets",
                "src/auth",
                "src/controller",
                "src/libs",
                "src/model",
                "src/portal",
                "src/route",
                "src/session",
                "src/styles")) {
            delete(project.buildRoot().resolve(path));
        }
    }

    private void deleteBuildSourceRootExceptFrontendDependencies(Path buildSourceRoot) throws IOException {
        Path nodeModules = buildSourceRoot.resolve("angular/node_modules");
        if (!Files.exists(nodeModules)) {
            delete(buildSourceRoot);
            return;
        }
        try (Stream<Path> paths = Files.walk(buildSourceRoot)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (item.equals(buildSourceRoot) || item.equals(nodeModules) || item.startsWith(nodeModules) || nodeModules.startsWith(item)) {
                    continue;
                }
                Files.deleteIfExists(item);
            }
        }
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
        delete(ProjectBuildLayout.generatedJavaSourceRoot(project));
        delete(ProjectBuildLayout.generatedResourcesRoot(project));
        writeGeneratedProjectMetadata(project);
        Path appRoot = ProjectBuildLayout.stagedAppRoot(project);
        if (Files.isDirectory(appRoot)) {
            try (Stream<Path> apps = Files.list(appRoot)) {
                for (Path app : apps.filter(Files::isDirectory).toList()) {
                    String appId = app.getFileName().toString();
                    Path appJson = app.resolve("app.json");
                    String apiHandlerClass = handlerClass(project, appId, appJson);
                    Optional<Path> apiSource = appJavaSource(app, "api.java", apiHandlerClass);
                    if (apiSource.isPresent()) {
                        writeJavaSource(project, apiHandlerClass, apiSource.get());
                    }
                    String socketHandlerClass = socketHandlerClass(project, appId, appJson);
                    Optional<Path> socketSource = appJavaSource(app, "socket.java", socketHandlerClass);
                    if (socketSource.isPresent()) {
                        writeJavaSource(project, socketHandlerClass, socketSource.get());
                    }
                }
            }
        }

        reconstructModelJava(project);
        reconstructRouteJava(project);

        Path controllerRoot = ProjectBuildLayout.stagedControllerRoot(project);
        reconstructJavaTree(project, controllerRoot, ProjectJavaNaming.packageRoot(project) + ".security.guard");
        // Legacy extension locations are still compiled for existing projects.
        // New projects should place auth/session implementations under src/model.
        reconstructJavaTree(project, ProjectBuildLayout.stagedSourceRoot(project).resolve("auth"), ProjectJavaNaming.packageRoot(project) + ".security.auth");
        reconstructJavaTree(project, ProjectBuildLayout.stagedSourceRoot(project).resolve("session"), ProjectJavaNaming.packageRoot(project) + ".security.session");
    }

    private Optional<Path> appJavaSource(Path app, String conventionalName, String handlerClass) {
        Path conventional = app.resolve(conventionalName);
        if (Files.isRegularFile(conventional)) {
            return Optional.of(conventional);
        }
        String handlerFileName = handlerClass.substring(handlerClass.lastIndexOf('.') + 1) + ".java";
        Path handlerNamed = app.resolve(handlerFileName);
        if (Files.isRegularFile(handlerNamed)) {
            return Optional.of(handlerNamed);
        }
        return Optional.empty();
    }

    private void reconstructRouteJava(ProjectContext project) throws IOException {
        Path routeRoot = ProjectBuildLayout.stagedRouteRoot(project);
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
        Path modelRoot = ProjectBuildLayout.stagedModelRoot(project);
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
            return portalModelHandlerClass(project, parts);
        }
        String root = ProjectJavaNaming.packageRoot(project);
        if (parts.length >= 2 && parts[0].equals("struct")) {
            return root + ".application.service." + relativeClass(parts, 1);
        }
        if (parts.length >= 2 && parts[0].equals("db")) {
            return root + ".domain.entity." + relativeClass(parts, 1);
        }
        if (parts.length >= 2 && parts[0].equals("orm")) {
            return root + ".infrastructure.orm." + relativeClass(parts, 1);
        }
        if (parts.length >= 2 && parts[0].equals("security")) {
            return root + ".security." + relativeClass(parts, 1);
        }
        return root + ".application.model." + relativeClass(parts, 0);
    }

    private String portalModelHandlerClass(ProjectContext project, String[] parts) {
        String root = ProjectJavaNaming.packageRoot(project) + ".module." + ProjectJavaNaming.packageSegment(parts[1]);
        if (parts.length >= 4 && parts[2].equals("struct")) {
            return root + ".application.service." + relativeClass(parts, 3);
        }
        if (parts.length >= 4 && parts[2].equals("db")) {
            return root + ".domain.entity." + relativeClass(parts, 3);
        }
        if (parts.length >= 4 && parts[2].equals("orm")) {
            return root + ".infrastructure.orm." + relativeClass(parts, 3);
        }
        if (parts.length >= 4 && parts[2].equals("security")) {
            return root + ".security." + relativeClass(parts, 3);
        }
        return root + ".application.model." + relativeClass(parts, 2);
    }

    private String relativeClass(String[] parts, int start) {
        if (start >= parts.length) {
            return "Generated";
        }
        List<String> segments = new ArrayList<>();
        for (int index = start; index < parts.length - 1; index++) {
            segments.add(ProjectJavaNaming.packageSegment(parts[index]));
        }
        segments.add(parts[parts.length - 1]);
        return String.join(".", segments);
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
        Path target = ProjectBuildLayout.generatedJavaSourceRoot(project).resolve(handlerClass.replace('.', '/') + ".java");
        Files.createDirectories(target.getParent());
        Files.writeString(target, javaSource(project, handlerClass, Files.readString(source)));
    }

    private void writeGeneratedProjectMetadata(ProjectContext project) throws IOException {
        Path pom = project.root().resolve("pom.xml");
        if (Files.isRegularFile(pom)) {
            copyFileIfExists(pom, ProjectBuildLayout.generatedPom(project));
        } else {
            Files.createDirectories(ProjectBuildLayout.generatedPom(project).getParent());
            Files.writeString(ProjectBuildLayout.generatedPom(project), generatedPom(project));
        }
        Files.createDirectories(ProjectBuildLayout.generatedResourcesRoot(project));
        copyIfExists(project.configRoot(), ProjectBuildLayout.generatedResourcesRoot(project));
    }

    private String generatedPom(ProjectContext project) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n"
                + "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
                + "    <modelVersion>4.0.0</modelVersion>\n"
                + "    <groupId>" + project.packageRoot() + "</groupId>\n"
                + "    <artifactId>wiz-generated-app</artifactId>\n"
                + "    <version>0.2.0</version>\n"
                + "    <properties>\n"
                + "        <java.version>21</java.version>\n"
                + "    </properties>\n"
                + "</project>\n";
    }

    private BuildResult compileProjectJava(ProjectContext project) throws IOException {
        return compileProjectJava(project, BuildLogger.quiet());
    }

    private BuildResult compileProjectJava(ProjectContext project, BuildLogger logger) throws IOException {
        Path sourceRoot = ProjectBuildLayout.generatedJavaSourceRoot(project);
        if (!Files.isDirectory(sourceRoot)) {
            logger.info("[java-compile] No Java app sources");
            return new BuildResult(0, List.of("reconstruct", "java-source", "app-dependencies", "java-compile"), "No Java app sources");
        }

        List<Path> sources;
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            sources = paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java")).toList();
        }
        if (sources.isEmpty()) {
            logger.info("[java-compile] No Java app sources");
            return new BuildResult(0, List.of("reconstruct", "java-source", "app-dependencies", "java-compile"), "No Java app sources");
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new BuildResult(1, List.of("reconstruct", "java-source", "app-dependencies", "java-compile"), "JDK compiler is required to build Java app APIs");
        }

        Path classesRoot = ProjectBuildLayout.classesRoot(project);
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
                return new BuildResult(1, List.of("reconstruct", "java-source", "app-dependencies", "java-compile"), text);
            }
        }

        packageProjectJar(project, classesRoot);
        logger.info("[java-compile] packaged " + ProjectBuildLayout.appApiJar(project));
        return new BuildResult(0, List.of("reconstruct", "java-source", "app-dependencies", "java-compile"), "Compiled Java app APIs");
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
        for (Path dependency : compilerDependencyJars(project)) {
            entries.add(dependency.toString());
        }
        return String.join(File.pathSeparator, entries);
    }

    private List<Path> compilerDependencyJars(ProjectContext project) throws IOException {
        LinkedHashSet<Path> jars = new LinkedHashSet<>();
        jars.addAll(jarsIn(ProjectBuildLayout.dependencyRoot(project)));
        jars.addAll(ProjectClassPath.dependencyJars(project));
        return List.copyOf(jars);
    }

    private List<Path> jarsIn(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> path.toAbsolutePath().normalize())
                    .toList();
        }
    }

    private void resolveProjectDependencies(ProjectContext project, BuildLogger logger) throws IOException {
        Path pom = project.root().resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            logger.info("[app-dependencies] no workspace pom.xml");
            return;
        }
        Path output = ProjectBuildLayout.dependencyRoot(project);
        Files.createDirectories(output);
        logger.info("[app-dependencies] pom: " + pom);
        logger.info("[app-dependencies] output: " + output);
        CommandResult result;
        try {
            result = new CommandExecutor().run(
                    "maven-dependencies",
                    project.root(),
                    project.root(),
                    List.of(
                            "mvn",
                            "--batch-mode",
                            "-f", pom.toString(),
                            "dependency:copy-dependencies",
                            "-DincludeScope=runtime",
                            "-DoutputDirectory=" + output),
                    Duration.ofMinutes(10),
                    1024 * 1024,
                    logger);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Project Maven dependency resolution was interrupted", exception);
        }
        logger.info("[app-dependencies] exitCode=" + result.exitCode()
                + " duration=" + formatDuration(result.durationMillis())
                + " timedOut=" + result.timedOut()
                + " cappedOutput=" + result.cappedOutput());
        if (result.exitCode() != 0) {
            throw new IOException("Project Maven dependency resolution failed with exit code " + result.exitCode()
                    + System.lineSeparator() + result.output());
        }
    }

    private boolean isBootJar(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return jar.getEntry("BOOT-INF/classes/") != null || jar.getEntry("BOOT-INF/classpath.idx") != null;
        } catch (IOException exception) {
            return false;
        }
    }

    private List<String> extractBootJarClasspath(ProjectContext project, Path jarPath) throws IOException {
        Path classpathRoot = ProjectBuildLayout.compilerClasspathRoot(project);
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
        copyIfExists(ProjectBuildLayout.frontendOutputRoot(project), project.bundleWwwRoot());
        copyIfExists(ProjectBuildLayout.stagedAssetsRoot(project), project.bundleAssetsRoot());
        copyIfExists(ProjectBuildLayout.stagedControllerRoot(project), project.bundleRoot().resolve("src/controller"));
        copyIfExists(ProjectBuildLayout.stagedModelRoot(project), project.bundleRoot().resolve("src/model"));
        copyIfExists(ProjectBuildLayout.stagedRouteRoot(project), project.bundleRoot().resolve("src/route"));
        copyIfExists(project.configRoot(), project.bundleRoot().resolve("config"));
        copyIfExists(ProjectBuildLayout.stagedAppRoot(project), project.bundleRoot().resolve("src/app"));
        copyIfExists(ProjectBuildLayout.dependencyRoot(project), project.bundleRoot().resolve("lib"));
        copyIfExists(project.root().resolve("lib"), project.bundleRoot().resolve("lib"));
        copyIfExists(ProjectBuildLayout.classesRoot(project), project.bundleRoot().resolve("classes"));
        copyFileIfExists(ProjectBuildLayout.appApiJar(project), project.bundleRoot().resolve("app-api.jar"));
        copyFileIfExists(ProjectBuildLayout.generatedPom(project), project.bundleRoot().resolve("pom.xml"));
    }

    private String handlerClass(ProjectContext project, String appId, Path appJson) throws IOException {
        if (Files.isRegularFile(appJson)) {
            Map<String, Object> metadata = objectMapper.readValue(Files.readAllBytes(appJson), new TypeReference<>() {
            });
            Optional<String> configured = javaHandlerClass(project, metadata);
            if (configured.isPresent()) {
                return configured.get();
            }
        }
        return ProjectJavaNaming.appApiHandlerClass(project, appId);
    }

    private Optional<String> javaHandlerClass(ProjectContext project, Map<String, Object> metadata) {
        Object api = metadata.get("api");
        if (!(api instanceof Map<?, ?> apiMap)) {
            return Optional.empty();
        }
        Object handler = apiMap.get("handler");
        if (handler == null || handler.toString().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(ProjectJavaNaming.modernizeProjectPackage(project, handler.toString()));
    }

    private String socketHandlerClass(ProjectContext project, String appId, Path appJson) throws IOException {
        if (Files.isRegularFile(appJson)) {
            Map<String, Object> metadata = objectMapper.readValue(Files.readAllBytes(appJson), new TypeReference<>() {
            });
            Optional<String> configured = javaSocketHandlerClass(project, metadata);
            if (configured.isPresent()) {
                return configured.get();
            }
        }
        return ProjectJavaNaming.appSocketHandlerClass(project, appId);
    }

    private Optional<String> javaSocketHandlerClass(ProjectContext project, Map<String, Object> metadata) {
        Object socket = metadata.get("socket");
        if (!(socket instanceof Map<?, ?> socketMap)) {
            return Optional.empty();
        }
        Object handler = socketMap.get("handler");
        if (handler == null || handler.toString().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(ProjectJavaNaming.modernizeProjectPackage(project, handler.toString()));
    }

    private String routeHandlerClass(ProjectContext project, String routeId, Path appJson) throws IOException {
        if (Files.isRegularFile(appJson)) {
            Map<String, Object> metadata = objectMapper.readValue(Files.readAllBytes(appJson), new TypeReference<>() {
            });
            Object handler = metadata.get("handler");
            if (handler != null && !handler.toString().isBlank()) {
                return ProjectJavaNaming.modernizeProjectPackage(project, handler.toString());
            }
        }
        return ProjectJavaNaming.routeHandlerClass(project, routeId);
    }

    private String javaSource(ProjectContext project, String handlerClass, String source) {
        String rewritten = ProjectJavaNaming.modernizeProjectPackages(project, source);
        if (rewritten.stripLeading().startsWith("package ")) {
            return rewritten;
        }
        int classStart = handlerClass.lastIndexOf('.');
        return "package " + handlerClass.substring(0, classStart) + ";\n\n" + rewritten;
    }

    private void packageProjectJar(ProjectContext project, Path classesRoot) throws IOException {
        Path jar = ProjectBuildLayout.appApiJar(project);
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
        FrontendRuntimeConfig frontendConfig = FrontendRuntimeConfig.from(project);
        return "const status = document.querySelector('[data-wiz-status]');\n"
                + "const apiPrefix = " + FrontendRuntimeConfig.stringLiteral(frontendConfig.apiPrefix()) + ";\n"
                + "fetch(`${apiPrefix}/page.dashboard/overview`, { method: 'POST' })\n"
                + "  .then((response) => response.json())\n"
                + "  .then((payload) => { if (status) status.textContent = `API ${payload.code}`; });\n";
    }

    private Path entryAppFile(ProjectContext project, String filename) {
        Path appRoot = ProjectBuildLayout.stagedAppRoot(project);
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
