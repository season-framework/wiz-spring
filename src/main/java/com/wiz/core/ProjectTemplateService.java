package com.wiz.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.lang.model.SourceVersion;

import com.wiz.security.GitUriPolicy;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Creates a standalone Spring project from the common embedded template and one
 * frontend-specific overlay. Generated projects do not depend on the wiz-spring
 * runtime after creation.
 */
public final class ProjectTemplateService {

    static final String COMMON_MANIFEST = "/wiz/templates/project-common.files";
    static final String COMMON_SAMPLE_MANIFEST = "/wiz/templates/project-common-sample.files";
    static final String COMMON_ROOT = "/wiz/templates/project-common/";
    private static final String TEMP_PREFIX = "wiz-spring-create-";
    private static final String PACKAGE_JSON = "package.json";
    private static final String FORBIDDEN_WIZ_NPM_PACKAGE = "@season-framework/wiz-frontend";
    private static final List<Path> ROOT_DEPENDENCY_LOCKFILES = List.of(
            Path.of("package-lock.json"),
            Path.of("npm-shrinkwrap.json"),
            Path.of("yarn.lock"),
            Path.of("pnpm-lock.yaml"),
            Path.of("pnpm-lock.yml"));
    private static final Path IMPORT_ARCHIVE_PARENT = Path.of("replaced-originals");
    private static final Set<Path> COMMON_AUTHORITATIVE_IMPORT_PATHS = Set.of(
            Path.of("AGENTS.md"),
            Path.of(".github/copilot-instructions.md"),
            Path.of("docs/ai/backend-spring.md"),
            Path.of("docs/ai/deployment.md"),
            Path.of(".mvn/wrapper/maven-wrapper.properties"),
            Path.of("mvnw"),
            Path.of("mvnw.cmd"),
            Path.of("pom.xml"));
    private static final Map<FrontendTemplate, Set<Path>> FRONTEND_AUTHORITATIVE_IMPORT_PATHS = Map.of(
            FrontendTemplate.ANGULAR_WIZ, Set.of(
                    Path.of("docs/ai/frontend.md"),
                    Path.of("angular.json"),
                    Path.of("src/angular/main.ts"),
                    Path.of("src/angular/tsconfig.json"),
                    Path.of("src/angular/tsconfig.app.json"),
                    Path.of("src/angular/types.d.ts"),
                    Path.of("src/angular/wiz.ts")),
            FrontendTemplate.ANGULAR, Set.of(
                    Path.of("docs/ai/frontend.md"),
                    Path.of("angular.json"),
                    Path.of("tsconfig.json"),
                    Path.of("tsconfig.app.json"),
                    Path.of("proxy.conf.cjs")),
            FrontendTemplate.REACT, Set.of(
                    Path.of("docs/ai/frontend.md"),
                    Path.of("vite.config.js")),
            FrontendTemplate.HTML, Set.of(Path.of("docs/ai/frontend.md")),
            FrontendTemplate.JSP, Set.of(Path.of("docs/ai/frontend.md")));
    private static final Path FORBIDDEN_WIZ_DIRECTORY = Path.of(".wiz");
    private static final Set<String> IMPORT_EXCLUDED_DIRECTORY_NAMES = Set.of(
            ".git", "node_modules");
    private static final Set<String> IMPORT_EXCLUDED_ROOT_DIRECTORIES = Set.of(
            ".angular", "build", "bundle", "dist", "target");
    private static final Pattern JAVA_PACKAGE_DECLARATION = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)\\s*;");
    private static final Pattern SPRING_BOOT_APPLICATION = Pattern.compile(
            "(?m)^\\s*@(?:org\\.springframework\\.boot\\.autoconfigure\\.)?SpringBootApplication\\b");
    private static final int IMPORT_PATH_PREVIEW_LIMIT = 8;
    private final TemplateResources resources;
    private final GitCloneCommand gitCloneCommand;
    private final ObjectMapper objectMapper;

    public ProjectTemplateService() {
        this(ProjectTemplateService::openClasspathResource, ProjectTemplateService::runGitClone, new ObjectMapper());
    }

    ProjectTemplateService(TemplateResources resources, GitCloneCommand gitCloneCommand) {
        this(resources, gitCloneCommand, new ObjectMapper());
    }

    ProjectTemplateService(
            TemplateResources resources,
            GitCloneCommand gitCloneCommand,
            ObjectMapper objectMapper) {
        this.resources = resources;
        this.gitCloneCommand = gitCloneCommand;
        this.objectMapper = objectMapper;
    }

    public GeneratedProject create(
            Path requestedPath,
            String packageRoot,
            FrontendTemplate template,
            String uri,
            Path sourcePath) throws IOException, InterruptedException {
        if (requestedPath == null) {
            throw new IllegalArgumentException("Project path is required");
        }
        if (template == null) {
            throw new IllegalArgumentException("Frontend template is required");
        }
        if (uri != null && uri.isBlank()) {
            throw new IllegalArgumentException("Git URI must not be blank");
        }
        if (uri != null && sourcePath != null) {
            throw new IllegalArgumentException("Use either --uri or --path, not both");
        }

        String normalizedPackage = validatePackageRoot(packageRoot);
        Path target = requestedPath.toAbsolutePath().normalize();
        if (Files.exists(target)) {
            throw new IllegalArgumentException("Project path already exists: " + target);
        }
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Project path must have a parent directory: " + target);
        }
        Files.createDirectories(parent);

        String artifactId = artifactId(target);
        boolean imported = hasImport(uri, sourcePath);
        Map<String, String> replacements = replacements(normalizedPackage, artifactId, template, imported);
        String overlayName = "project-" + template.id();
        String overlayManifest = "/wiz/templates/" + overlayName + ".files";
        String overlaySampleManifest = "/wiz/templates/" + overlayName + "-sample.files";
        String overlayRoot = "/wiz/templates/" + overlayName + "/";
        Path staging = Files.createTempDirectory(parent, TEMP_PREFIX);
        try {
            if (uri != null && !uri.isBlank()) {
                cloneProject(uri, staging);
            } else if (sourcePath != null) {
                copyImportedProject(sourcePath, staging);
            }

            if (imported) {
                validateImportedProjectPolicy(staging);
                validateImportedJavaSources(staging, normalizedPackage);
                validateImportedFrontendLayout(staging, template);
                prepareImportedProject(staging, replacements, overlayManifest, template);
            }
            Set<Path> importedFiles = imported ? existingRelativePaths(staging) : Set.of();
            LinkedHashMap<String, Object> overlayPackage = readOverlayPackage(
                    overlayRoot + PACKAGE_JSON, replacements, template);
            if (imported) {
                rejectManagedInfrastructureConflicts(
                        staging, replacements, overlayManifest, overlayPackage);
            }
            Set<Path> generatedFiles = new HashSet<>();
            applyLayer(staging, COMMON_MANIFEST, COMMON_ROOT, replacements, importedFiles, generatedFiles);
            applyLayer(
                    staging,
                    overlayManifest,
                    overlayRoot,
                    replacements,
                    importedFiles,
                    generatedFiles);
            if (!imported) {
                applyLayer(
                        staging,
                        COMMON_SAMPLE_MANIFEST,
                        COMMON_ROOT,
                        replacements,
                        importedFiles,
                        generatedFiles);
                applyLayer(
                        staging,
                        overlaySampleManifest,
                        overlayRoot,
                        replacements,
                        importedFiles,
                        generatedFiles);
            }
            if (importedFiles.contains(Path.of(PACKAGE_JSON))) {
                mergeImportedPackage(staging, overlayPackage, template);
            }
            moveCompletedProject(staging, target);
            return new GeneratedProject(target, normalizedPackage, artifactId, template, imported);
        } finally {
            deleteIfExists(staging);
        }
    }

    private String validatePackageRoot(String packageRoot) {
        if (packageRoot == null || packageRoot.isBlank()) {
            throw new IllegalArgumentException("Java package root is required");
        }
        String value = packageRoot.trim();
        if (value.startsWith("java.") || value.equals("java")) {
            throw new IllegalArgumentException("Java package root must not use the java namespace");
        }
        for (String segment : value.split("\\.", -1)) {
            if (SourceVersion.isKeyword(segment, SourceVersion.RELEASE_25)) {
                throw new IllegalArgumentException(
                        "Java package root must not contain a Java 25 keyword: " + segment);
            }
            if (!SourceVersion.isIdentifier(segment)) {
                throw new IllegalArgumentException(
                        "Java package root must contain only Java 25 identifiers");
            }
        }
        return value;
    }

    private void validateImportedJavaSources(Path staging, String expectedPackage) throws IOException {
        Path sourceRoot = staging.resolve("src");
        if (!Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        Path mainJava = sourceRoot.resolve("main/java");
        Path testJava = sourceRoot.resolve("test/java");
        List<Path> javaSources;
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            javaSources = paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }

        List<Path> nonStandard = javaSources.stream()
                .filter(path -> !path.startsWith(mainJava) && !path.startsWith(testJava))
                .toList();
        if (!nonStandard.isEmpty()) {
            throw importJavaError(
                    staging,
                    "non-standard Java sources are outside src/main/java and would not be compiled",
                    nonStandard,
                    "Move or rewrite them as ordinary Spring classes under src/main/java before importing.");
        }

        ArrayList<Path> packageMismatches = new ArrayList<>();
        ArrayList<Path> bootApplications = new ArrayList<>();
        for (Path source : javaSources.stream().filter(path -> path.startsWith(mainJava)).toList()) {
            String contents = Files.readString(source, StandardCharsets.UTF_8);
            Matcher packageMatcher = JAVA_PACKAGE_DECLARATION.matcher(contents);
            if (!packageMatcher.find()) {
                packageMismatches.add(source);
            } else {
                String declaredPackage = packageMatcher.group(1);
                if (!declaredPackage.equals(expectedPackage)
                        && !declaredPackage.startsWith(expectedPackage + ".")) {
                    packageMismatches.add(source);
                }
            }
            if (SPRING_BOOT_APPLICATION.matcher(contents).find()) {
                bootApplications.add(source);
            }
        }
        if (!packageMismatches.isEmpty()) {
            throw importJavaError(
                    staging,
                    "Spring Java packages must be " + expectedPackage + " or one of its subpackages",
                    packageMismatches,
                    "Move or rewrite these sources to the requested --package before importing so Spring component scanning cannot silently omit them.");
        }

        Path expectedApplication = mainJava
                .resolve(expectedPackage.replace('.', '/'))
                .resolve("Application.java")
                .normalize();
        if (javaSources.contains(expectedApplication) && !bootApplications.contains(expectedApplication)) {
            throw importJavaError(
                    staging,
                    "an imported file blocks the generated Spring Boot application but is not annotated with @SpringBootApplication",
                    List.of(expectedApplication),
                    "Annotate that application correctly or remove it before importing.");
        }
        if (bootApplications.size() > 1
                || (bootApplications.size() == 1 && !bootApplications.get(0).equals(expectedApplication))) {
            throw importJavaError(
                    staging,
                    "the imported Spring Boot application must be a single "
                            + sourceRoot.relativize(expectedApplication).toString().replace('\\', '/'),
                    bootApplications,
                    "Keep one @SpringBootApplication at the generated package root before importing.");
        }
    }

    private IllegalArgumentException importJavaError(
            Path staging,
            String reason,
            List<Path> sources,
            String remediation) {
        String preview = sources.stream()
                .limit(IMPORT_PATH_PREVIEW_LIMIT)
                .map(staging::relativize)
                .map(path -> path.toString().replace('\\', '/'))
                .collect(java.util.stream.Collectors.joining(", "));
        if (sources.size() > IMPORT_PATH_PREVIEW_LIMIT) {
            preview += ", ...";
        }
        return new IllegalArgumentException(
                "Cannot import Java backend: " + reason + " (" + sources.size() + " file(s): " + preview + "). "
                        + remediation
                        + " The source directory was not modified and no target project was published.");
    }

    private void prepareImportedProject(
            Path staging,
            Map<String, String> replacements,
            String overlayManifest,
            FrontendTemplate template) throws IOException {
        ImportArchive archive = new ImportArchive(staging);
        Set<Path> embeddedPaths = embeddedTargetPaths(replacements, overlayManifest);
        HashSet<Path> authoritativePaths = new HashSet<>(COMMON_AUTHORITATIVE_IMPORT_PATHS);
        authoritativePaths.addAll(FRONTEND_AUTHORITATIVE_IMPORT_PATHS.getOrDefault(template, Set.of()));
        authoritativePaths.retainAll(embeddedPaths);
        for (Path path : authoritativePaths.stream().sorted().toList()) {
            archive.moveIfPresent(path);
        }
    }

    private void validateImportedProjectPolicy(Path staging) throws IOException {
        if (Files.exists(staging.resolve(FORBIDDEN_WIZ_DIRECTORY), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                    "Cannot import a project containing .wiz. WIZ Spring 1.0 stores frontend metadata "
                            + "in package.json and never creates or publishes a .wiz directory. "
                            + "The source directory was not modified and no target project was published.");
        }

        Path packageJson = staging.resolve(PACKAGE_JSON);
        if (Files.isRegularFile(packageJson, LinkOption.NOFOLLOW_LINKS)) {
            byte[] contents = Files.readAllBytes(packageJson);
            String text = decodeUtf8(contents, PACKAGE_JSON);
            if (text.contains(FORBIDDEN_WIZ_NPM_PACKAGE)) {
                throw forbiddenExternalBuilder(PACKAGE_JSON);
            }
            LinkedHashMap<String, Object> manifest = readPackageJson(contents, PACKAGE_JSON);
            if (manifest.containsKey("wiz")) {
                throw new IllegalArgumentException(
                        "Cannot import a project that already declares the managed package.json field 'wiz'. "
                                + "Remove or rename that field before project generation. "
                                + "The source directory was not modified and no target project was published.");
            }
        }

        for (Path relativePath : ROOT_DEPENDENCY_LOCKFILES) {
            Path lockfile = staging.resolve(relativePath);
            if (Files.isRegularFile(lockfile, LinkOption.NOFOLLOW_LINKS)
                    && decodeUtf8(Files.readAllBytes(lockfile), relativePath.toString())
                            .contains(FORBIDDEN_WIZ_NPM_PACKAGE)) {
                throw forbiddenExternalBuilder(relativePath.toString().replace('\\', '/'));
            }
        }
    }

    private IllegalArgumentException forbiddenExternalBuilder(String source) {
        return new IllegalArgumentException(
                "Cannot import " + source + " because it references the forbidden external builder "
                        + FORBIDDEN_WIZ_NPM_PACKAGE + ". The WIZ frontend builder must be injected "
                        + "as project-local scripts. "
                        + "The source directory was not modified and no target project was published.");
    }

    private void validateImportedFrontendLayout(Path staging, FrontendTemplate template) {
        List<RequiredImportPath> requirements = switch (template) {
            case ANGULAR_WIZ -> List.of(
                    RequiredImportPath.directory("src/app"));
            case ANGULAR -> List.of(
                    RequiredImportPath.file("frontend/src/index.html"),
                    RequiredImportPath.file("frontend/src/main.ts"),
                    RequiredImportPath.file("frontend/src/styles.css"));
            case REACT -> List.of(
                    RequiredImportPath.file("frontend/index.html"),
                    RequiredImportPath.directory("frontend/src"));
            case HTML -> List.of(
                    RequiredImportPath.file("frontend/index.html"));
            case JSP -> List.of(
                    RequiredImportPath.directory("src/main/webapp/WEB-INF/jsp"));
        };
        List<String> missing = requirements.stream()
                .filter(requirement -> !requirement.matches(staging))
                .map(RequiredImportPath::display)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot import the selected '" + template.id() + "' frontend: required 1.0 layout path(s) "
                            + String.join(", ", missing) + " are missing or have the wrong type. "
                            + "Move the frontend into the selected template layout before importing. "
                            + "The source directory was not modified and no target project was published.");
        }
    }

    private Set<Path> embeddedTargetPaths(
            Map<String, String> replacements,
            String overlayManifest) throws IOException {
        HashSet<Path> paths = new HashSet<>();
        for (String manifest : List.of(COMMON_MANIFEST, overlayManifest)) {
            for (String entry : manifestEntries(manifest)) {
                validateManifestEntry(entry, manifest);
                String targetEntry = replace(entry, replacements);
                validateManifestEntry(targetEntry, manifest);
                paths.add(Path.of(targetEntry).normalize());
            }
        }
        return Set.copyOf(paths);
    }

    private static final class ImportArchive {
        private final Path staging;
        private Path root;

        private ImportArchive(Path staging) {
            this.staging = staging;
        }

        private void moveIfPresent(Path relativePath) throws IOException {
            Path source = staging.resolve(relativePath).normalize();
            if (!source.startsWith(staging)
                    || !Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            Path destination = root().resolve(archiveRelativePath(relativePath)).normalize();
            if (!destination.startsWith(root())) {
                throw new IllegalArgumentException("Imported archive path escapes project root: " + relativePath);
            }
            Files.createDirectories(destination.getParent());
            Files.move(source, destination);
        }

        private Path root() throws IOException {
            if (root != null) {
                return root;
            }
            Path archiveParent = availableArchiveParent();
            Path candidate = archiveParent.resolve("wiz-spring-import");
            int suffix = 2;
            while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                candidate = archiveParent.resolve("wiz-spring-import-" + suffix++);
            }
            Files.createDirectories(candidate);
            Files.writeString(candidate.resolve("README.md"), """
                    # Inactive imported originals

                    These standard project files were replaced by the selected 1.0 template.
                    They are reference copies only: generated
                    builds, AI tools, and deployment commands do not read configuration from here.
                    Review and delete them after carrying over any settings you still need. Manually
                    merge any still-required Maven dependencies and plugins from the archived
                    `pom.xml` into the generated root `pom.xml`; the archived POM is never built.
                    """, StandardCharsets.UTF_8);
            root = candidate;
            return root;
        }

        private Path availableArchiveParent() throws IOException {
            Path candidate = staging.resolve(IMPORT_ARCHIVE_PARENT);
            int suffix = 2;
            while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                candidate = staging.resolve(IMPORT_ARCHIVE_PARENT + "-" + suffix++);
            }
            Files.createDirectories(candidate);
            return candidate;
        }

        private Path archiveRelativePath(Path relativePath) {
            Path archived = Path.of("");
            for (Path segment : relativePath) {
                String name = segment.toString().replaceFirst("^\\.+", "");
                archived = archived.resolve(name.isEmpty() ? "root" : name);
            }
            return archived;
        }
    }

    private void rejectManagedInfrastructureConflicts(
            Path staging,
            Map<String, String> replacements,
            String overlayManifest,
            Map<String, Object> overlayPackage) throws IOException {
        Set<Path> managedPaths = new HashSet<>();
        for (String manifest : List.of(COMMON_MANIFEST, overlayManifest)) {
            for (String entry : manifestEntries(manifest)) {
                validateManifestEntry(entry, manifest);
                String targetEntry = replace(entry, replacements);
                validateManifestEntry(targetEntry, manifest);
                Path path = Path.of(targetEntry).normalize();
                if (isManagedInfrastructurePath(path)
                        || referencedByManagedPackageScript(path, overlayPackage)) {
                    managedPaths.add(path);
                }
            }
        }

        List<String> conflicts = managedPaths.stream()
                .filter(path -> importedPathBlocks(staging, path))
                .map(path -> path.toString().replace('\\', '/'))
                .sorted()
                .toList();
        if (!conflicts.isEmpty()) {
            throw new IllegalArgumentException(
                    "Imported project conflicts with managed build/deploy infrastructure: "
                            + String.join(", ", conflicts)
                            + ". Rename or remove these files before importing.");
        }
    }

    private boolean isManagedInfrastructurePath(Path path) {
        if (path.getNameCount() == 0) {
            return false;
        }
        String first = path.getName(0).toString();
        return first.equals("scripts")
                || first.equals("deploy")
                || path.toString().replace('\\', '/').equals("docker-compose.yaml");
    }

    private boolean referencedByManagedPackageScript(Path path, Map<String, Object> overlayPackage) {
        Map<String, Object> scripts = optionalObject(
                overlayPackage.get("scripts"), "embedded package.json field 'scripts'");
        String normalizedPath = path.toString().replace('\\', '/');
        return scripts.values().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(command -> command.contains(normalizedPath));
    }

    private boolean importedPathBlocks(Path staging, Path managedPath) {
        Path current = staging;
        for (Path segment : managedPath) {
            current = current.resolve(segment);
            if (Files.isRegularFile(current) || Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return Files.exists(staging.resolve(managedPath));
    }

    private void applyLayer(
            Path staging,
            String manifestResource,
            String resourceRoot,
            Map<String, String> replacements,
            Set<Path> importedFiles,
            Set<Path> generatedFiles) throws IOException {
        List<String> entries = manifestEntries(manifestResource);
        Set<String> uniqueEntries = new HashSet<>();
        for (String sourceEntry : entries) {
            if (!uniqueEntries.add(sourceEntry)) {
                throw new IllegalArgumentException(
                        "Duplicate embedded template entry in " + manifestResource + ": " + sourceEntry);
            }
            validateManifestEntry(sourceEntry, manifestResource);
            String targetEntry = replace(sourceEntry, replacements);
            validateManifestEntry(targetEntry, manifestResource);
            Path relativeTarget = Path.of(targetEntry).normalize();
            Path target = staging.resolve(relativeTarget).normalize();
            if (!target.startsWith(staging)) {
                throw new IllegalArgumentException("Embedded template entry escapes project root: " + targetEntry);
            }
            if (importedFiles.contains(relativeTarget)) {
                continue;
            }

            byte[] contents;
            try (InputStream input = requiredResource(resourceRoot + sourceEntry)) {
                contents = input.readAllBytes();
            }
            contents = replacePlaceholdersIfText(contents, replacements, resourceRoot + sourceEntry);
            Files.createDirectories(target.getParent());
            if (generatedFiles.contains(relativeTarget)) {
                Files.write(target, contents);
            } else if (!Files.exists(target)) {
                Files.write(target, contents);
                generatedFiles.add(relativeTarget);
            }
            makeExecutableWhenNeeded(targetEntry, target);
        }
    }

    private List<String> manifestEntries(String resourcePath) throws IOException {
        String manifest;
        try (InputStream input = requiredResource(resourcePath)) {
            manifest = decodeUtf8(input.readAllBytes(), resourcePath);
        }
        ArrayList<String> entries = new ArrayList<>();
        for (String line : manifest.split("\\R")) {
            String entry = line.strip();
            if (entry.startsWith("\uFEFF")) {
                entry = entry.substring(1).strip();
            }
            if (!entry.isEmpty() && !entry.startsWith("#")) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    static void validateManifestEntry(String entry, String manifestResource) {
        if (entry == null || entry.isBlank()
                || entry.startsWith("/")
                || entry.startsWith("\\")
                || entry.contains("\\")
                || entry.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "Unsafe embedded template entry in " + manifestResource + ": " + entry);
        }
        Path path;
        try {
            path = Path.of(entry);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Unsafe embedded template entry in " + manifestResource + ": " + entry, exception);
        }
        if (path.isAbsolute()) {
            throw new IllegalArgumentException(
                    "Unsafe embedded template entry in " + manifestResource + ": " + entry);
        }
        for (Path part : path) {
            if (part.toString().equals("..") || part.toString().equals(".")) {
                throw new IllegalArgumentException(
                        "Unsafe embedded template entry in " + manifestResource + ": " + entry);
            }
        }
        if (!path.normalize().toString().equals(entry)) {
            throw new IllegalArgumentException(
                    "Non-normalized embedded template entry in " + manifestResource + ": " + entry);
        }
    }

    private InputStream requiredResource(String resourcePath) throws IOException {
        InputStream input = resources.open(resourcePath);
        if (input == null) {
            throw new IOException("Embedded project template resource is missing: " + resourcePath);
        }
        return input;
    }

    private byte[] replacePlaceholdersIfText(
            byte[] contents,
            Map<String, String> replacements,
            String resourcePath) throws IOException {
        for (byte value : contents) {
            if (value == 0) {
                return contents;
            }
        }
        try {
            String text = decodeUtf8(contents, resourcePath);
            String replaced = replace(text, replacements);
            rejectForbiddenGeneratedPackage(replaced, resourcePath);
            return replaced.getBytes(StandardCharsets.UTF_8);
        } catch (CharacterCodingException exception) {
            return contents;
        }
    }

    private LinkedHashMap<String, Object> readOverlayPackage(
            String resourcePath,
            Map<String, String> replacements,
            FrontendTemplate template) throws IOException {
        String json;
        try (InputStream input = requiredResource(resourcePath)) {
            json = replace(decodeUtf8(input.readAllBytes(), resourcePath), replacements);
        }
        rejectForbiddenGeneratedPackage(json, resourcePath);
        LinkedHashMap<String, Object> overlay = readPackageJson(json.getBytes(StandardCharsets.UTF_8), resourcePath);
        Map<String, Object> wiz = requiredObject(overlay.get("wiz"), resourcePath + " field 'wiz'");
        if (!template.id().equals(wiz.get("frontend"))) {
            throw new IllegalArgumentException(
                    "Embedded package.json frontend does not match selected template " + template.id());
        }
        return overlay;
    }

    private void mergeImportedPackage(
            Path staging,
            LinkedHashMap<String, Object> overlay,
            FrontendTemplate template) throws IOException {
        Path packageJson = staging.resolve(PACKAGE_JSON);
        LinkedHashMap<String, Object> imported = readPackageJson(Files.readAllBytes(packageJson), packageJson.toString());

        for (Map.Entry<String, Object> entry : overlay.entrySet()) {
            if (!Set.of("wiz", "scripts", "dependencies", "devDependencies", "allowScripts")
                    .contains(entry.getKey())) {
                imported.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }

        Map<String, Object> overlayWiz = requiredObject(overlay.get("wiz"), "embedded package.json field 'wiz'");
        LinkedHashMap<String, Object> importedWiz = new LinkedHashMap<>();
        importedWiz.putAll(overlayWiz);
        importedWiz.put("frontend", template.id());
        imported.put("wiz", importedWiz);

        LinkedHashMap<String, Object> importedScripts = mutableObject(
                imported.get("scripts"), "imported package.json field 'scripts'");
        Map<String, Object> overlayScripts = optionalObject(
                overlay.get("scripts"), "embedded package.json field 'scripts'");
        for (Map.Entry<String, Object> script : overlayScripts.entrySet()) {
            importedScripts.put(script.getKey(), script.getValue());
        }
        imported.put("scripts", importedScripts);

        LinkedHashMap<String, Object> importedAllowScripts = mutableObject(
                imported.get("allowScripts"), "imported package.json field 'allowScripts'");
        Map<String, Object> overlayAllowScripts = optionalObject(
                overlay.get("allowScripts"), "embedded package.json field 'allowScripts'");
        for (Map.Entry<String, Object> approval : overlayAllowScripts.entrySet()) {
            importedAllowScripts.putIfAbsent(approval.getKey(), approval.getValue());
        }
        if (!importedAllowScripts.isEmpty() || imported.containsKey("allowScripts")) {
            imported.put("allowScripts", importedAllowScripts);
        }

        LinkedHashMap<String, Object> importedDependencies = mutableObject(
                imported.get("dependencies"), "imported package.json field 'dependencies'");
        LinkedHashMap<String, Object> importedDevDependencies = mutableObject(
                imported.get("devDependencies"), "imported package.json field 'devDependencies'");
        mergeMissingDependencies(
                importedDependencies,
                importedDevDependencies,
                optionalObject(overlay.get("dependencies"), "embedded package.json field 'dependencies'"));
        mergeMissingDependencies(
                importedDevDependencies,
                importedDependencies,
                optionalObject(overlay.get("devDependencies"), "embedded package.json field 'devDependencies'"));
        if (!importedDependencies.isEmpty() || imported.containsKey("dependencies")) {
            imported.put("dependencies", importedDependencies);
        }
        if (!importedDevDependencies.isEmpty() || imported.containsKey("devDependencies")) {
            imported.put("devDependencies", importedDevDependencies);
        }
        String generatedPackageJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(imported) + "\n";
        Files.writeString(
                packageJson,
                generatedPackageJson,
                StandardCharsets.UTF_8);
    }

    private LinkedHashMap<String, Object> readPackageJson(byte[] json, String source) throws IOException {
        try {
            LinkedHashMap<String, Object> value = objectMapper.readValue(
                    json, new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            if (value == null) {
                throw new IllegalArgumentException("package.json must contain a JSON object: " + source);
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid package.json: " + source, exception);
        }
    }

    private LinkedHashMap<String, Object> mutableObject(Object value, String label) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(label + " must be a JSON object");
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private Map<String, Object> requiredObject(Object value, String label) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(label + " must be a JSON object");
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private Map<String, Object> optionalObject(Object value, String label) {
        return value == null ? Map.of() : requiredObject(value, label);
    }

    private void mergeMissingDependencies(
            LinkedHashMap<String, Object> destination,
            Map<String, Object> oppositeSection,
            Map<String, Object> requiredDependencies) {
        for (Map.Entry<String, Object> dependency : requiredDependencies.entrySet()) {
            String name = dependency.getKey();
            Object requiredVersion = dependency.getValue();
            rejectDependencyVersionConflict(name, destination, requiredVersion);
            rejectDependencyVersionConflict(name, oppositeSection, requiredVersion);
            if (!destination.containsKey(name) && !oppositeSection.containsKey(name)) {
                destination.put(name, requiredVersion);
            }
        }
    }

    private void rejectDependencyVersionConflict(
            String name,
            Map<String, Object> importedDependencies,
            Object requiredVersion) {
        if (importedDependencies.containsKey(name)
                && !Objects.equals(importedDependencies.get(name), requiredVersion)) {
            throw new IllegalArgumentException(
                    "Dependency version conflict for '" + name + "': imported "
                            + importedDependencies.get(name) + ", template requires " + requiredVersion);
        }
    }

    private void rejectForbiddenGeneratedPackage(String contents, String resourcePath) {
        if (contents.contains(FORBIDDEN_WIZ_NPM_PACKAGE)) {
            throw new IllegalArgumentException(
                    "Embedded project templates must not depend on " + FORBIDDEN_WIZ_NPM_PACKAGE
                            + ": " + resourcePath);
        }
    }

    private String decodeUtf8(byte[] contents, String resourcePath) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(contents))
                .toString();
    }

    private String replace(String value, Map<String, String> replacements) {
        String replaced = value;
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            replaced = replaced.replace(replacement.getKey(), replacement.getValue());
        }
        return replaced;
    }

    private Map<String, String> replacements(
            String packageRoot,
            String artifactId,
            FrontendTemplate template,
            boolean imported) {
        Map<String, String> values = new HashMap<>();
        values.put("__WIZ_PACKAGE_ROOT__", packageRoot);
        values.put("__WIZ_PACKAGE_PATH__", packageRoot.replace('.', '/'));
        values.put("__WIZ_ARTIFACT_ID__", artifactId);
        values.put("__WIZ_PROJECT_NAME__", artifactId);
        values.put("__WIZ_COMPOSE_PROJECT__", artifactId.replace('.', '-'));
        values.put("__WIZ_FRONTEND__", template.id());
        values.put("__WIZ_ARTIFACT_TYPE__", template == FrontendTemplate.JSP ? "war" : "jar");
        values.put("__WIZ_SAMPLE_DEPENDENCIES__", imported ? "" : sampleDependencies());
        values.put("__WIZ_SAMPLE_SPRING_CONFIGURATION__", imported ? "" : sampleSpringConfiguration());
        return Map.copyOf(values);
    }

    private String sampleDependencies() {
        return """
                <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
                        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
                        <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>runtime</scope></dependency>
                        <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-crypto</artifactId></dependency>""";
    }

    private String sampleSpringConfiguration() {
        return """
                datasource:
                    url: ${APP_DATASOURCE_URL:jdbc:h2:file:./data/sample}
                    username: ${APP_DATASOURCE_USERNAME:sa}
                    password: ${APP_DATASOURCE_PASSWORD:}
                  jpa:
                    open-in-view: false
                    hibernate:
                      ddl-auto: update""";
    }

    private void makeExecutableWhenNeeded(String entry, Path target) throws IOException {
        String name = target.getFileName().toString();
        if (!name.equals("mvnw") && !(entry.startsWith("scripts/") && name.endsWith(".sh"))) {
            return;
        }
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(target, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows and other non-POSIX filesystems do not expose executable bits.
        }
    }

    private Set<Path> existingRelativePaths(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private void copyImportedProject(Path sourcePath, Path staging) throws IOException {
        Path source = sourcePath.toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("Source path must be a directory: " + source);
        }
        Path realSource = source.toRealPath();
        Path realStaging = staging.toRealPath();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Path realDirectory = directory.toRealPath();
                if (isExcludedImportDirectory(source, directory)
                        || realDirectory.equals(realStaging)
                        || realDirectory.startsWith(realStaging)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path destination = importDestination(source, staging, directory);
                Files.createDirectories(destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Path relative = source.relativize(file);
                String filename = file.getFileName().toString();
                if (filename.equals(".git")) {
                    return FileVisitResult.CONTINUE;
                }
                if (Files.isSymbolicLink(file)) {
                    throw new IllegalArgumentException("Symbolic links are not allowed in project imports: " + relative);
                }
                Path destination = importDestination(source, staging, file);
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean isExcludedImportDirectory(Path source, Path directory) {
        if (directory.equals(source)) {
            return false;
        }
        String name = directory.getFileName().toString();
        if (IMPORT_EXCLUDED_DIRECTORY_NAMES.contains(name)) {
            return true;
        }
        return source.relativize(directory).getNameCount() == 1
                && IMPORT_EXCLUDED_ROOT_DIRECTORIES.contains(name);
    }

    private Path importDestination(Path source, Path staging, Path item) {
        Path destination = staging.resolve(source.relativize(item)).normalize();
        if (!destination.startsWith(staging)) {
            throw new IllegalArgumentException("Project import escapes staging directory");
        }
        return destination;
    }

    private void cloneProject(String uri, Path staging) throws IOException, InterruptedException {
        String validatedUri = GitUriPolicy.validate(uri);
        Path clone = Files.createTempDirectory(staging.getParent(), "wiz-spring-clone-");
        try {
            gitCloneCommand.clone(validatedUri, clone);
            copyImportedProject(clone, staging);
        } finally {
            deleteIfExists(clone);
        }
    }

    static ProcessBuilder gitCloneProcessBuilder(String uri, Path cloneTarget) {
        ProcessBuilder builder = new ProcessBuilder("git", "clone", "--", uri, cloneTarget.toString())
                .inheritIO();
        builder.environment().put("GIT_TERMINAL_PROMPT", "1");
        return builder;
    }

    private static void runGitClone(String uri, Path cloneTarget) throws IOException, InterruptedException {
        Process process = gitCloneProcessBuilder(uri, cloneTarget).start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("git clone failed with exit code " + exitCode);
        }
    }

    private void moveCompletedProject(Path staging, Path target) throws IOException {
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(staging, target);
        }
    }

    private String artifactId(Path target) {
        Path fileName = target.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("Project path must include a project name: " + target);
        }
        String artifact = fileName.toString().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]+", "-")
                .replaceAll("^[._-]+|[._-]+$", "");
        if (artifact.isBlank()) {
            throw new IllegalArgumentException("Project path must include a Maven-compatible project name: " + target);
        }
        return artifact;
    }

    private boolean hasImport(String uri, Path sourcePath) {
        return (uri != null && !uri.isBlank()) || sourcePath != null;
    }

    private void deleteIfExists(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    private static InputStream openClasspathResource(String resourcePath) {
        return ProjectTemplateService.class.getResourceAsStream(resourcePath);
    }

    private record RequiredImportPath(Path path, boolean directory) {
        private static RequiredImportPath file(String path) {
            return new RequiredImportPath(Path.of(path), false);
        }

        private static RequiredImportPath directory(String path) {
            return new RequiredImportPath(Path.of(path), true);
        }

        private boolean matches(Path root) {
            Path candidate = root.resolve(path);
            return directory
                    ? Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                    : Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS);
        }

        private String display() {
            return path.toString().replace('\\', '/') + (directory ? "/" : "");
        }
    }

    @FunctionalInterface
    interface TemplateResources {
        InputStream open(String resourcePath) throws IOException;
    }

    @FunctionalInterface
    interface GitCloneCommand {
        void clone(String uri, Path cloneTarget) throws IOException, InterruptedException;
    }

    public record GeneratedProject(
            Path root,
            String packageRoot,
            String artifactId,
            FrontendTemplate template,
            boolean imported) {
    }
}
