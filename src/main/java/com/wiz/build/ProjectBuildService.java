package com.wiz.build;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.wiz.core.ProjectJavaNaming;
import com.wiz.core.WorkspacePackageService;
import com.wiz.runtime.BuildMarkerService;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.WorkspaceRuntimePaths;

import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProjectBuildService {

    private static final List<String> SUPPORTED_PHASES = List.of("reconstruct", "compile", "bundle");
    private static final ConcurrentHashMap<Path, BuildLockEntry> LOCKS = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AngularBuildService angularBuildService;
    private final MavenDependencyCache mavenDependencyCache;
    private final BootJarClasspathCache bootJarClasspathCache;
    private final BuildMarkerService buildMarkerService = new BuildMarkerService();

    public ProjectBuildService() {
        this(new AngularBuildService(), new MavenDependencyCache(), new BootJarClasspathCache());
    }

    ProjectBuildService(AngularBuildService angularBuildService) {
        this(angularBuildService, new MavenDependencyCache(), new BootJarClasspathCache());
    }

    ProjectBuildService(
            AngularBuildService angularBuildService,
            MavenDependencyCache mavenDependencyCache,
            BootJarClasspathCache bootJarClasspathCache) {
        this.angularBuildService = angularBuildService;
        this.mavenDependencyCache = mavenDependencyCache;
        this.bootJarClasspathCache = bootJarClasspathCache;
    }

    public BuildResult build(ProjectContext project, boolean clean, String phase) throws IOException {
        return build(project, clean, phase, BuildLogger.quiet());
    }

    public BuildResult build(ProjectContext project, boolean clean, String phase, BuildLogger logger) throws IOException {
        BuildLogger buildLogger = logger == null ? BuildLogger.quiet() : logger;
        String requestedPhase = phase == null || phase.isBlank() ? "bundle" : phase;
        if (!isSupportedPhase(requestedPhase)) {
            return new BuildResult(2, List.of(requestedPhase), "Supported build phases: reconstruct, compile, bundle");
        }
        if (!hasBuildSource(project.appRoot())) {
            return new BuildResult(2, List.of(requestedPhase), missingBuildSourceMessage(project.root()));
        }
        validateManagedOutputPaths(project);

        return withWorkspaceBuildLock(project.root(), buildLogger,
                () -> runBuildLocked(project, clean, requestedPhase, buildLogger));
    }

    public PackageBuildResult build(
            PathService paths,
            String requestedPackageRoot,
            boolean clean,
            String phase,
            BuildLogger logger) throws IOException {
        BuildLogger buildLogger = logger == null ? BuildLogger.quiet() : logger;
        String requestedPhase = phase == null || phase.isBlank() ? "bundle" : phase;
        if (!isSupportedPhase(requestedPhase)) {
            return new PackageBuildResult(
                    new BuildResult(2, List.of(requestedPhase), "Supported build phases: reconstruct, compile, bundle"),
                    false,
                    paths.packageRoot());
        }
        if (!hasBuildSource(paths.root().resolve("src/app"))) {
            return new PackageBuildResult(
                    new BuildResult(2, List.of(requestedPhase), missingBuildSourceMessage(paths.root())),
                    false,
                    paths.packageRoot());
        }
        return withWorkspaceBuildLock(paths.root(), buildLogger, () -> {
            WorkspacePackageService.PackageSelection selection = new WorkspacePackageService()
                    .selectForBuild(paths, requestedPackageRoot);
            ProjectContext project = selection.context();
            validateManagedOutputPaths(project);
            BuildResult result = runBuildLocked(
                    project,
                    clean || selection.changed(),
                    requestedPhase,
                    buildLogger);
            return new PackageBuildResult(result, selection.changed(), project.packageRoot());
        });
    }

    private BuildResult runBuildLocked(
            ProjectContext project,
            boolean clean,
            String requestedPhase,
            BuildLogger buildLogger) throws IOException {
        Instant startedAt = Instant.now();
        long totalStarted = System.nanoTime();
        buildLogger.info("== WIZ app build ==");
        buildLogger.info("Workspace: " + project.root());
        buildLogger.info("Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        buildLogger.info("Java home: " + System.getProperty("java.home"));
        buildLogger.info("Clean: " + clean);
        buildLogger.info("Phase: " + requestedPhase);
        return buildLocked(project, clean, requestedPhase, buildLogger, startedAt, totalStarted);
    }

    private <T> T withWorkspaceBuildLock(Path workspaceRoot, BuildLogger buildLogger, BuildStep<T> action) throws IOException {
        Path normalizedRoot = workspaceRoot.toAbsolutePath().normalize();
        Path lockPath = WorkspaceRuntimePaths.buildLock(normalizedRoot);
        BuildLockEntry entry = LOCKS.compute(lockPath, (ignored, current) -> {
            BuildLockEntry selected = current == null ? new BuildLockEntry() : current;
            selected.references++;
            return selected;
        });
        entry.lock.lock();
        try {
            Path lockFile = WorkspaceRuntimePaths.prepareBuildLock(normalizedRoot);
            buildLogger.info("Build lock: " + lockFile);
            try (FileChannel lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                WorkspaceRuntimePaths.secureFile(lockFile);
                try (FileLock ignored = lockChannel.lock()) {
                    return action.run();
                }
            }
        } finally {
            entry.lock.unlock();
            LOCKS.computeIfPresent(lockPath, (ignored, current) -> {
                if (current != entry) {
                    return current;
                }
                entry.references--;
                return entry.references == 0 ? null : entry;
            });
        }
    }

    public static boolean isSupportedPhase(String phase) {
        return SUPPORTED_PHASES.contains(phase);
    }

    public static boolean hasBuildSource(Path appRoot) {
        return Files.isDirectory(appRoot, LinkOption.NOFOLLOW_LINKS);
    }

    public static String missingBuildSourceMessage(Path workspaceRoot) {
        return "WIZ Spring build requires source directory " + workspaceRoot.resolve("src/app")
                + ". A deploy-only bundle can be run or packaged with --skip-build, but it cannot be rebuilt.";
    }

    private BuildResult buildLocked(ProjectContext project, boolean clean, String requestedPhase,
            BuildLogger buildLogger, Instant startedAt, long totalStarted) throws IOException {
        recoverInterruptedBundlePublish(project, buildLogger);
        if (clean) {
            timed(buildLogger, "clean", () -> {
                delete(project.buildRoot());
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
        if (!frontend.built()) {
            delete(ProjectBuildLayout.frontendOutputRoot(project));
        }

        List<String> phases = List.of("reconstruct", "java-source", "app-dependencies", "java-compile", frontend.phase(), "bundle");
        Path stagingRoot = ProjectBuildLayout.bundleStagingRoot(project);
        ProjectContext stagingProject = withBundleRoot(project, stagingRoot);
        delete(stagingRoot);
        try {
            timed(buildLogger, "bundle", () -> {
                assembleBundle(project, stagingProject);
                return null;
            });
            if (!frontend.built()) {
                timed(buildLogger, "frontend-fallback", () -> {
                    writeMinimalWebBundle(stagingProject);
                    return null;
                });
            }
            SupplyChainManifestService.Result supplyChain = timed(buildLogger, "supply-chain",
                    () -> new SupplyChainManifestService().write(stagingProject, Instant.now()));
            buildMarkerService.write(stagingProject, phases, frontend.built() ? "real" : "fallback", startedAt, Instant.now(),
                    new BuildMarkerService.DependencySummary(
                            "bundle/" + SupplyChainManifestService.DEPENDENCY_MANIFEST_FILE,
                            supplyChain.digestAlgorithm(),
                            supplyChain.dependencyDigest(),
                            supplyChain.dependencyCount(),
                            "bundle/" + SupplyChainManifestService.CYCLONEDX_BOM_FILE));
            timed(buildLogger, "bundle-publish", () -> {
                replaceDirectory(stagingRoot, project.bundleRoot(), ProjectBuildLayout.bundlePreviousRoot(project), buildLogger);
                return null;
            });
        } finally {
            delete(stagingRoot);
        }
        return finish(buildLogger, totalStarted, new BuildResult(0, phases, "Generated Java WIZ app bundle"));
    }

    private void recoverInterruptedBundlePublish(ProjectContext project, BuildLogger logger) throws IOException {
        Path previous = ProjectBuildLayout.bundlePreviousRoot(project);
        if (Files.notExists(previous)) {
            return;
        }
        if (Files.notExists(project.bundleRoot())) {
            Files.createDirectories(project.bundleRoot().getParent());
            moveDirectory(previous, project.bundleRoot());
            logger.info("[bundle-recovery] restored the last published bundle after an interrupted replacement");
            return;
        }
        try {
            delete(previous);
        } catch (IOException cleanupFailure) {
            logger.info("[bundle-recovery] previous bundle cleanup deferred: " + previous
                    + " (" + cleanupFailure.getMessage() + ")");
        }
    }

    public void reconstruct(ProjectContext project) throws IOException {
        validateManagedOutputPaths(project);
        reconstruct(project, false);
    }

    private void reconstruct(ProjectContext project, boolean preserveFrontendDependencies) throws IOException {
        Files.createDirectories(project.buildRoot());
        Path buildSourceRoot = ProjectBuildLayout.stagedSourceRoot(project);
        if (preserveFrontendDependencies) {
            deleteBuildSourceRootExceptFrontendCaches(buildSourceRoot);
        } else {
            delete(buildSourceRoot);
        }
        Files.createDirectories(buildSourceRoot);
        copyIfExists(project.sourceRoot(), buildSourceRoot);
        flattenPortals(project.sourceRoot().resolve("portal"), buildSourceRoot);
        new AppMetadataNormalizer(objectMapper).normalize(project, buildSourceRoot);
    }

    private void deleteBuildSourceRootExceptFrontendCaches(Path buildSourceRoot) throws IOException {
        Path angularRoot = buildSourceRoot.resolve("angular");
        Path nodeModules = angularRoot.resolve("node_modules");
        Path angularState = angularRoot.resolve(".angular");
        Path angularCache = angularState.resolve("cache");
        boolean preserveNodeModules = Files.isDirectory(angularRoot, LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(nodeModules, LinkOption.NOFOLLOW_LINKS);
        boolean preserveAngularCache = Files.isDirectory(angularRoot, LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(angularState, LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(angularCache, LinkOption.NOFOLLOW_LINKS);
        if (!preserveNodeModules && !preserveAngularCache) {
            delete(buildSourceRoot);
            return;
        }

        deleteChildrenExcept(buildSourceRoot, List.of(angularRoot));
        ArrayList<Path> preservedAngularChildren = new ArrayList<>();
        if (preserveNodeModules) {
            preservedAngularChildren.add(nodeModules);
        }
        if (preserveAngularCache) {
            preservedAngularChildren.add(angularState);
        }
        deleteChildrenExcept(angularRoot, preservedAngularChildren);
        if (preserveAngularCache) {
            deleteChildrenExcept(angularState, List.of(angularCache));
        }
    }

    private void deleteChildrenExcept(Path directory, List<Path> preservedChildren) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.toList()) {
                if (!preservedChildren.contains(child)) {
                    delete(child);
                }
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

    private static final class BuildLockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }

    public record PackageBuildResult(BuildResult result, boolean packageChanged, String packageRoot) {
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
                + "    <version>0.2.7</version>\n"
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
            deleteJavaOutputs(project);
            logger.info("[java-compile] No Java app sources");
            return new BuildResult(0, List.of("reconstruct", "java-source", "app-dependencies", "java-compile"), "No Java app sources");
        }

        List<Path> sources;
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            sources = paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java")).toList();
        }
        if (sources.isEmpty()) {
            deleteJavaOutputs(project);
            logger.info("[java-compile] No Java app sources");
            return new BuildResult(0, List.of("reconstruct", "java-source", "app-dependencies", "java-compile"), "No Java app sources");
        }

        deleteJavaOutputs(project);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new BuildResult(1, List.of("reconstruct", "java-source", "app-dependencies", "java-compile"), "JDK compiler is required to build Java app APIs");
        }

        Path classesRoot = ProjectBuildLayout.classesRoot(project);
        Files.createDirectories(classesRoot);
        logger.info("[java-compile] source files: " + sources.size());
        logger.info("[java-compile] output: " + classesRoot);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StringWriter output = new StringWriter();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(sources);
            List<String> options = List.of(
                    "--release", "21",
                    "-classpath", compilerClasspath(project, logger),
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
                deleteJavaOutputs(project);
                return new BuildResult(1, List.of("reconstruct", "java-source", "app-dependencies", "java-compile"), text);
            }
        }

        packageProjectJar(project, classesRoot);
        logger.info("[java-compile] packaged " + ProjectBuildLayout.appApiJar(project));
        return new BuildResult(0, List.of("reconstruct", "java-source", "app-dependencies", "java-compile"), "Compiled Java app APIs");
    }

    private void deleteJavaOutputs(ProjectContext project) throws IOException {
        delete(ProjectBuildLayout.classesRoot(project));
        Files.deleteIfExists(ProjectBuildLayout.appApiJar(project));
    }

    private String compilerClasspath(ProjectContext project, BuildLogger logger) throws IOException {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        LinkedHashSet<String> activeBootCacheKeys = new LinkedHashSet<>();
        String classpath = System.getProperty("java.class.path", "");
        if (!classpath.isBlank()) {
            for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) {
                    entries.add(entry);
                    Path path = Path.of(entry);
                    if (Files.isRegularFile(path)) {
                        Optional<BootJarClasspathCache.Result> cached = bootJarClasspathCache.resolve(
                                project.root(), path, logger);
                        if (cached.isPresent()) {
                            BootJarClasspathCache.Result result = cached.get();
                            activeBootCacheKeys.add(result.key());
                            result.classpathEntries().stream()
                                    .map(Path::toString)
                                    .forEach(entries::add);
                        }
                    }
                }
            }
        }
        if (!activeBootCacheKeys.isEmpty()) {
            bootJarClasspathCache.prune(project.root(), activeBootCacheKeys, logger);
        }
        for (Path dependency : compilerDependencyJars(project)) {
            entries.add(dependency.toString());
        }
        return String.join(File.pathSeparator, entries);
    }

    private List<Path> compilerDependencyJars(ProjectContext project) throws IOException {
        LinkedHashMap<String, Path> jars = new LinkedHashMap<>();
        for (Path jar : jarsIn(ProjectBuildLayout.dependencyRoot(project))) {
            jars.put(jar.getFileName().toString(), jar);
        }
        for (Path jar : jarsIn(project.root().resolve("lib"))) {
            jars.put(jar.getFileName().toString(), jar);
        }
        return List.copyOf(jars.values());
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
        mavenDependencyCache.resolve(project, logger);
    }

    private void replaceDirectory(Path source, Path target, Path previous, BuildLogger logger) throws IOException {
        Files.createDirectories(previous.getParent());
        if (Files.notExists(target) && Files.exists(previous)) {
            moveDirectory(previous, target);
        }
        delete(previous);
        boolean hadPrevious = Files.exists(target);
        if (hadPrevious) {
            moveDirectory(target, previous);
        }
        try {
            moveDirectory(source, target);
        } catch (IOException failure) {
            if (hadPrevious && Files.notExists(target) && Files.exists(previous)) {
                try {
                    moveDirectory(previous, target);
                } catch (IOException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
            }
            throw failure;
        }
        try {
            delete(previous);
        } catch (IOException cleanupFailure) {
            logger.info("[cleanup] published output is ready, but previous directory could not be removed: "
                    + previous + " (" + cleanupFailure.getMessage() + ")");
        }
    }

    private void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    public void bundle(ProjectContext project) throws IOException {
        validateManagedOutputPaths(project);
        Path stagingRoot = ProjectBuildLayout.bundleStagingRoot(project);
        delete(stagingRoot);
        try {
            assembleBundle(project, withBundleRoot(project, stagingRoot));
            replaceDirectory(stagingRoot, project.bundleRoot(), ProjectBuildLayout.bundlePreviousRoot(project), BuildLogger.quiet());
        } finally {
            delete(stagingRoot);
        }
    }

    private void assembleBundle(ProjectContext sourceProject, ProjectContext destinationProject) throws IOException {
        Files.createDirectories(destinationProject.bundleRoot());
        copyIfExists(ProjectBuildLayout.frontendOutputRoot(sourceProject), destinationProject.bundleWwwRoot());
        copyIfExists(ProjectBuildLayout.stagedAssetsRoot(sourceProject), destinationProject.bundleAssetsRoot());
        copyIfExists(ProjectBuildLayout.stagedControllerRoot(sourceProject), destinationProject.bundleRoot().resolve("src/controller"));
        copyIfExists(ProjectBuildLayout.stagedModelRoot(sourceProject), destinationProject.bundleRoot().resolve("src/model"));
        copyIfExists(ProjectBuildLayout.stagedRouteRoot(sourceProject), destinationProject.bundleRoot().resolve("src/route"));
        copyIfExists(sourceProject.configRoot(), destinationProject.bundleRoot().resolve("config"));
        copyIfExists(ProjectBuildLayout.stagedAppRoot(sourceProject), destinationProject.bundleRoot().resolve("src/app"));
        copyJarDirectory(ProjectBuildLayout.dependencyRoot(sourceProject), destinationProject.bundleRoot().resolve("lib"));
        copyIfExists(sourceProject.root().resolve("lib"), destinationProject.bundleRoot().resolve("lib"));
        copyIfExists(ProjectBuildLayout.classesRoot(sourceProject), destinationProject.bundleRoot().resolve("classes"));
        copyFileIfExists(ProjectBuildLayout.appApiJar(sourceProject), destinationProject.bundleRoot().resolve("app-api.jar"));
        copyFileIfExists(ProjectBuildLayout.generatedPom(sourceProject), destinationProject.bundleRoot().resolve("pom.xml"));
    }

    private void copyJarDirectory(Path source, Path target) throws IOException {
        for (Path jar : jarsIn(source)) {
            copyFileIfExists(jar, target.resolve(jar.getFileName().toString()));
        }
    }

    private ProjectContext withBundleRoot(ProjectContext project, Path bundleRoot) {
        return new ProjectContext(
                project.name(),
                project.packageRoot(),
                project.root(),
                project.sourceRoot(),
                project.appRoot(),
                project.modelRoot(),
                project.routeRoot(),
                project.assetsRoot(),
                project.configRoot(),
                project.buildRoot(),
                bundleRoot);
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
            for (Path item : paths
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> classesRoot.relativize(path).toString()))
                    .toList()) {
                String entryName = classesRoot.relativize(item).toString().replace('\\', '/');
                JarEntry entry = new JarEntry(entryName);
                entry.setTime(0L);
                output.putNextEntry(entry);
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

    private void validateManagedOutputPaths(ProjectContext project) {
        Path workspaceRoot = project.root().toAbsolutePath().normalize();
        Path expectedBuildRoot = workspaceRoot.resolve("build");
        Path expectedBundleRoot = workspaceRoot.resolve("bundle");
        if (!project.buildRoot().toAbsolutePath().normalize().equals(expectedBuildRoot)
                || !project.bundleRoot().toAbsolutePath().normalize().equals(expectedBundleRoot)) {
            throw new IllegalArgumentException("WIZ build outputs must use the workspace build/ and bundle/ directories");
        }
        for (Path managedPath : List.of(
                ProjectBuildLayout.stagedSourceRoot(project),
                ProjectBuildLayout.generatedJavaSourceRoot(project),
                ProjectBuildLayout.generatedResourcesRoot(project),
                ProjectBuildLayout.generatedPom(project),
                ProjectBuildLayout.classesRoot(project),
                ProjectBuildLayout.appApiJar(project),
                ProjectBuildLayout.dependencyRoot(project),
                ProjectBuildLayout.dependencyStagingRoot(project),
                ProjectBuildLayout.frontendOutputRoot(project),
                ProjectBuildLayout.frontendDependencyFingerprint(project),
                ProjectBuildLayout.bundleStagingRoot(project),
                ProjectBuildLayout.bundlePreviousRoot(project),
                project.bundleRoot())) {
            rejectSymbolicLinkAncestors(workspaceRoot, managedPath);
        }
    }

    private void rejectSymbolicLinkAncestors(Path workspaceRoot, Path managedPath) {
        Path normalized = managedPath.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("WIZ managed build path escapes the workspace: " + managedPath);
        }
        Path current = workspaceRoot;
        for (Path segment : workspaceRoot.relativize(normalized)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Symbolic links are not allowed in managed build paths: " + current);
            }
        }
    }

    private void delete(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}
