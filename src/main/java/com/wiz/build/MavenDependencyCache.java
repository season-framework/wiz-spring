package com.wiz.build;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import com.wiz.runtime.ProjectContext;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves workspace Maven dependencies while reusing a verified, immutable
 * directory from the preceding build when all relevant local inputs are stable.
 */
final class MavenDependencyCache {

    static final String STATE_FILE = ".maven-dependency-cache.json";

    private static final int SCHEMA_VERSION = 1;
    private static final long MAX_STATE_BYTES = 1024 * 1024;
    private static final Duration RESOLUTION_TIMEOUT = Duration.ofMinutes(10);
    private static final int OUTPUT_CAP_BYTES = 1024 * 1024;
    private static final Pattern VERSION_RANGE = Pattern.compile(
            "(?is)>\\s*[\\[(][^<]*[,][^<]*[\\])]\\s*<");
    private static final Pattern DYNAMIC_VERSION = Pattern.compile(
            "(?is)>\\s*(?:LATEST|RELEASE)\\s*<");
    private static final Pattern SNAPSHOT_VERSION = Pattern.compile(
            "(?is)>\\s*[^<]*SNAPSHOT[^<]*<");
    private static final Pattern DYNAMIC_PROPERTY_OPTION = Pattern.compile(
            "(?is)(?:^|\\s)-D[^=\\s]+\\s*=\\s*(?:"
                    + "[\"']?(?:LATEST|RELEASE|[^\\s\"']*SNAPSHOT)[\"']?"
                    + "|[\"']?[\\[(][^\\r\\n,]*,[^\\r\\n]*[\\])][\"']?"
                    + ")(?:\\s|$)");
    private static final Pattern XML_COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern PROJECT_MODEL = Pattern.compile(
            "(?is)<(?:[^:>]+:)?project(?:\\s|>)");
    private static final Pattern SETTINGS_MODEL = Pattern.compile(
            "(?is)<(?:[^:>]+:)?settings(?:\\s|>)");
    private static final Pattern ACTIVATION = Pattern.compile("(?is)<(?:[^:>]+:)?activation(?:\\s|>)");
    private static final Pattern SYSTEM_PATH = Pattern.compile("(?is)<(?:[^:>]+:)?systemPath(?:\\s|>)");
    private static final Pattern UPDATE_SNAPSHOTS = Pattern.compile("(?m)(?:^|\\s)(?:-U|--update-snapshots)(?:\\s|$)");
    private static final Pattern EXPLICIT_SETTINGS = Pattern.compile(
            "(?m)(?:^|\\s)(?:-s|--settings)(?:\\s|=|$)");
    private static final List<String> USER_MAVEN_FILES = List.of(
            "settings.xml",
            "toolchains.xml",
            "settings-security.xml",
            "extensions.xml",
            "maven.config");

    private final CommandExecutor commandExecutor;
    private final Map<String, String> environment;
    private final ObjectMapper objectMapper;

    MavenDependencyCache() {
        this(new CommandExecutor());
    }

    MavenDependencyCache(CommandExecutor commandExecutor) {
        this(commandExecutor, System.getenv());
    }

    MavenDependencyCache(CommandExecutor commandExecutor, Map<String, String> environment) {
        this.commandExecutor = commandExecutor;
        this.environment = environment == null ? Map.of() : Map.copyOf(environment);
        this.objectMapper = new ObjectMapper();
    }

    void resolve(ProjectContext project, BuildLogger logger) throws IOException {
        BuildLogger buildLogger = logger == null ? BuildLogger.quiet() : logger;
        Path pom = project.root().resolve("pom.xml");
        Path output = ProjectBuildLayout.dependencyRoot(project);
        Path staging = ProjectBuildLayout.dependencyStagingRoot(project);
        Path previous = previousDirectory(output);

        if (!Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS)) {
            delete(staging);
            delete(output);
            delete(previous);
            buildLogger.info("[app-dependencies] no workspace pom.xml");
            return;
        }

        recoverInterruptedPublish(output, previous, buildLogger);

        // Resolution is deliberately required even on a cache hit. Besides being
        // part of the key, this preserves the CLI contract that Maven must exist.
        Path mavenExecutable = MavenExecutableResolver.require(project.root());
        Fingerprint fingerprint = fingerprint(project, pom, mavenExecutable);
        CacheValidation validation = validateCache(output, fingerprint);
        if (fingerprint.cacheable() && validation.hit()) {
            buildLogger.info("[app-dependencies] cache hit: " + validation.detail());
            return;
        }

        String missReason = fingerprint.cacheable() ? validation.detail() : fingerprint.bypassReason();
        buildLogger.info("[app-dependencies] cache "
                + (fingerprint.cacheable() ? "miss: " : "bypass: ") + missReason);

        delete(staging);
        Files.createDirectories(staging);
        try {
            CommandResult result = commandExecutor.run(
                    "maven-dependencies",
                    project.root(),
                    project.root(),
                    List.of(
                            "mvn",
                            "--batch-mode",
                            "-f", pom.toString(),
                            "dependency:copy-dependencies",
                            "-DincludeScope=runtime",
                            "-DoutputDirectory=" + staging),
                    RESOLUTION_TIMEOUT,
                    OUTPUT_CAP_BYTES,
                    buildLogger);
            buildLogger.info("[app-dependencies] exitCode=" + result.exitCode()
                    + " duration=" + result.durationMillis() + "ms"
                    + " timedOut=" + result.timedOut()
                    + " cappedOutput=" + result.cappedOutput());
            if (!result.success()) {
                throw new IOException("Project Maven dependency resolution failed with exit code " + result.exitCode()
                        + System.lineSeparator() + result.output());
            }

            JarSnapshot snapshot = snapshotJars(staging, Set.of());
            boolean cacheable = fingerprint.cacheable() && snapshot.cacheable();
            String bypassReason = !fingerprint.cacheable()
                    ? fingerprint.bypassReason()
                    : snapshot.bypassReason();
            writeState(staging, fingerprint.digest(), cacheable, bypassReason, snapshot.jars());
            publish(staging, output, previous, buildLogger);
            buildLogger.info("[app-dependencies] installed: " + output
                    + (cacheable ? " (cache ready)" : " (cache disabled: " + bypassReason + ")"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Project Maven dependency resolution was interrupted", exception);
        } finally {
            delete(staging);
        }
    }

    private Fingerprint fingerprint(ProjectContext project, Path pom, Path mavenExecutable) throws IOException {
        InputFiles inputs = inputFiles(project, pom, mavenExecutable);
        MessageDigest digest = sha256Digest();
        update(digest, "maven-dependency-cache-schema");
        update(digest, Integer.toString(SCHEMA_VERSION));
        update(digest, "dependency:copy-dependencies");
        update(digest, "includeScope=runtime");
        update(digest, project.root().toAbsolutePath().normalize().toString());

        addExecutableFingerprint(digest, mavenExecutable);
        for (Path input : inputs.paths().stream().sorted(Comparator.comparing(Path::toString)).toList()) {
            addFileFingerprint(digest, input);
        }

        for (String key : List.of("PATH", "HOME", "JAVA_HOME", "MAVEN_OPTS")) {
            update(digest, "env:" + key);
            update(digest, environment.getOrDefault(key, ""));
        }
        update(digest, "env:CI");
        update(digest, "true");
        update(digest, "env:NO_COLOR");
        update(digest, "1");
        for (String key : List.of(
                "java.version", "java.vendor", "java.home",
                "os.name", "os.arch", "os.version")) {
            update(digest, "property:" + key);
            update(digest, System.getProperty(key, ""));
        }

        DynamicInput dynamic = dynamicInput(inputs.paths());
        String environmentReason = dynamicText(
                environment.getOrDefault("MAVEN_OPTS", ""), "MAVEN_OPTS");
        String bypassReason = inputs.bypassReason();
        if (bypassReason == null && dynamic.reason() != null) {
            bypassReason = dynamic.reason();
        }
        if (bypassReason == null && environmentReason != null) {
            bypassReason = environmentReason;
        }
        return new Fingerprint(HexFormat.of().formatHex(digest.digest()), bypassReason == null, bypassReason);
    }

    private InputFiles inputFiles(ProjectContext project, Path pom, Path mavenExecutable) throws IOException {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        ArrayList<String> bypassReasons = new ArrayList<>();
        collectPomAndLocalParents(pom, paths, bypassReasons, new LinkedHashSet<>());
        collectDirectory(project.root().resolve(".mvn"), paths, bypassReasons);

        Path wrapper = project.root().resolve(isWindows() ? "mvnw.cmd" : "mvnw");
        addOptionalFile(wrapper, paths, bypassReasons, "Maven wrapper");
        collectMavenHomeConfiguration(mavenExecutable, paths, bypassReasons);

        String homeValue = environment.get("HOME");
        if (homeValue != null && !homeValue.isBlank()) {
            Path userMavenRoot;
            try {
                userMavenRoot = Path.of(homeValue).toAbsolutePath().normalize().resolve(".m2");
            } catch (RuntimeException exception) {
                bypassReasons.add("HOME does not resolve to a valid path");
                userMavenRoot = null;
            }
            if (userMavenRoot != null) {
                for (String name : USER_MAVEN_FILES) {
                    addOptionalFile(userMavenRoot.resolve(name), paths, bypassReasons, "user Maven " + name);
                }
            }
        }

        Path javaHome = environmentPath("JAVA_HOME");
        if (javaHome != null) {
            addOptionalFile(javaHome.resolve("release"), paths, bypassReasons, "JAVA_HOME release metadata");
            Path java = javaHome.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
            addOptionalFile(java, paths, bypassReasons, "JAVA_HOME Java executable");
        }

        String reason = bypassReasons.isEmpty() ? null : String.join("; ", bypassReasons);
        return new InputFiles(List.copyOf(paths), reason);
    }

    private void collectMavenHomeConfiguration(
            Path executable,
            LinkedHashSet<Path> paths,
            List<String> bypassReasons) {
        try {
            Path real = executable.toRealPath();
            Path bin = real.getParent();
            Path mavenHome = bin == null ? null : bin.getParent();
            if (mavenHome == null) {
                return;
            }
            for (String name : List.of("settings.xml", "toolchains.xml")) {
                addOptionalFile(mavenHome.resolve("conf").resolve(name), paths, bypassReasons,
                        "Maven installation " + name);
            }
        } catch (IOException exception) {
            bypassReasons.add("Maven installation configuration could not be inspected");
        }
    }

    private void collectPomAndLocalParents(
            Path pom,
            LinkedHashSet<Path> paths,
            List<String> bypassReasons,
            Set<Path> visited) throws IOException {
        Path normalized = pom.toAbsolutePath().normalize();
        if (!visited.add(normalized)) {
            bypassReasons.add("local Maven parent cycle detected at " + normalized);
            return;
        }
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            bypassReasons.add("local Maven model is not a regular file: " + normalized);
            return;
        }
        paths.add(normalized);

        ParentReference parent;
        try {
            parent = localParent(normalized);
        } catch (Exception exception) {
            bypassReasons.add("Maven model could not be inspected safely: " + normalized);
            return;
        }
        if (parent == null || parent.disabled()) {
            return;
        }
        if (parent.relativePath().contains("${")) {
            bypassReasons.add("property-based Maven parent relativePath requires Maven resolution: " + normalized);
            return;
        }
        Path candidate = normalized.getParent().resolve(parent.relativePath()).normalize();
        if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            candidate = candidate.resolve("pom.xml");
        }
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(candidate)) {
                bypassReasons.add("local Maven parent is symbolic: " + candidate);
                return;
            }
            collectPomAndLocalParents(candidate, paths, bypassReasons, visited);
        }
    }

    private ParentReference localParent(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element project;
        try (InputStream input = Files.newInputStream(pom)) {
            project = factory.newDocumentBuilder().parse(input).getDocumentElement();
        }
        Element parent = directChild(project, "parent");
        if (parent == null) {
            return null;
        }
        Element relative = directChild(parent, "relativePath");
        if (relative == null) {
            return new ParentReference("../pom.xml", false);
        }
        String value = relative.getTextContent().trim();
        return value.isEmpty() ? new ParentReference("", true) : new ParentReference(value, false);
    }

    private Element directChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element
                    && (localName.equals(element.getLocalName()) || localName.equals(element.getNodeName()))) {
                return element;
            }
        }
        return null;
    }

    private void collectDirectory(
            Path directory,
            LinkedHashSet<Path> paths,
            List<String> bypassReasons) throws IOException {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            bypassReasons.add("Maven configuration path is not a directory: " + directory);
            return;
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            for (Path item : stream.sorted(Comparator.comparing(Path::toString)).toList()) {
                if (item.equals(directory)) {
                    continue;
                }
                if (Files.isSymbolicLink(item)) {
                    bypassReasons.add("Maven configuration contains a symbolic path: " + item);
                } else if (Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)) {
                    paths.add(item.toAbsolutePath().normalize());
                }
            }
        }
    }

    private void addOptionalFile(
            Path path,
            LinkedHashSet<Path> paths,
            List<String> bypassReasons,
            String description) {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            bypassReasons.add(description + " is not a direct regular file: " + path);
            return;
        }
        paths.add(path.toAbsolutePath().normalize());
    }

    private DynamicInput dynamicInput(List<Path> paths) throws IOException {
        for (Path path : paths) {
            if (!isTextInput(path)) {
                continue;
            }
            String reason = dynamicText(Files.readString(path, StandardCharsets.UTF_8), path.toString());
            if (reason != null) {
                return new DynamicInput(reason);
            }
        }
        return new DynamicInput(null);
    }

    private String dynamicText(String text, String source) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (DYNAMIC_PROPERTY_OPTION.matcher(text).find()) {
            return "dynamic Maven property requires Maven resolution (" + source + ")";
        }
        String modelText = XML_COMMENT.matcher(text).replaceAll("");
        boolean projectModel = PROJECT_MODEL.matcher(modelText).find();
        boolean settingsModel = SETTINGS_MODEL.matcher(modelText).find();
        if (projectModel || settingsModel) {
            if (SNAPSHOT_VERSION.matcher(modelText).find()) {
                return "SNAPSHOT input requires Maven resolution (" + source + ")";
            }
            if (DYNAMIC_VERSION.matcher(modelText).find() || VERSION_RANGE.matcher(modelText).find()) {
                return "dynamic Maven version requires Maven resolution (" + source + ")";
            }
        }
        if (projectModel) {
            if (SYSTEM_PATH.matcher(modelText).find()) {
                return "systemPath dependency requires Maven resolution (" + source + ")";
            }
        }
        if ((projectModel || settingsModel) && ACTIVATION.matcher(modelText).find()) {
            return "profile activation requires Maven resolution (" + source + ")";
        }
        if (UPDATE_SNAPSHOTS.matcher(text).find()) {
            return "Maven update-snapshots mode disables dependency caching (" + source + ")";
        }
        if (source.endsWith("maven.config")
                && EXPLICIT_SETTINGS.matcher(text).find()) {
            return "explicit Maven settings require Maven resolution (" + source + ")";
        }
        return null;
    }

    private boolean isTextInput(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".xml")
                || name.endsWith(".config")
                || name.endsWith(".properties")
                || name.equals("pom.xml")
                || name.equals("mvnw")
                || name.equals("mvnw.cmd");
    }

    private CacheValidation validateCache(Path output, Fingerprint fingerprint) throws IOException {
        if (!fingerprint.cacheable()) {
            return new CacheValidation(false, fingerprint.bypassReason());
        }
        if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
            return new CacheValidation(false, "no published dependency directory");
        }
        Path stateFile = output.resolve(STATE_FILE);
        if (!Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            return new CacheValidation(false, "dependency cache state is missing");
        }
        if (Files.size(stateFile) > MAX_STATE_BYTES) {
            return new CacheValidation(false, "dependency cache state exceeds the validation limit");
        }

        Map<String, Object> state;
        try {
            state = objectMapper.readValue(Files.readAllBytes(stateFile), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IOException | RuntimeException exception) {
            return new CacheValidation(false, "dependency cache state is invalid");
        }
        if (number(state.get("schemaVersion")) != SCHEMA_VERSION) {
            return new CacheValidation(false, "dependency cache schema changed");
        }
        if (!Boolean.TRUE.equals(state.get("cacheable"))) {
            return new CacheValidation(false, "published dependency state is not cacheable");
        }
        if (!fingerprint.digest().equals(String.valueOf(state.get("inputFingerprint")))) {
            return new CacheValidation(false, "Maven inputs changed");
        }

        List<JarArtifact> expected = jarArtifacts(state.get("jars"));
        if (expected == null) {
            return new CacheValidation(false, "dependency cache JAR manifest is invalid");
        }
        JarSnapshot actual = snapshotJars(output, Set.of(STATE_FILE));
        if (!actual.cacheable()) {
            return new CacheValidation(false, actual.bypassReason());
        }
        if (!expected.equals(actual.jars())) {
            return new CacheValidation(false, "resolved dependency JARs changed");
        }
        return new CacheValidation(true, expected.size() + " verified JAR(s)");
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : -1;
    }

    private List<JarArtifact> jarArtifacts(Object value) {
        if (!(value instanceof List<?> values)) {
            return null;
        }
        ArrayList<JarArtifact> artifacts = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> map)) {
                return null;
            }
            Object name = map.get("name");
            Object size = map.get("size");
            Object sha = map.get("sha256");
            if (!(name instanceof String fileName)
                    || fileName.isBlank()
                    || fileName.contains("/")
                    || fileName.contains("\\")
                    || !(size instanceof Number fileSize)
                    || fileSize.longValue() < 0
                    || !(sha instanceof String checksum)
                    || !checksum.matches("[0-9a-f]{64}")) {
                return null;
            }
            artifacts.add(new JarArtifact(fileName, fileSize.longValue(), checksum));
        }
        artifacts.sort(Comparator.comparing(JarArtifact::name));
        return List.copyOf(artifacts);
    }

    private JarSnapshot snapshotJars(Path directory, Set<String> allowedFiles) throws IOException {
        ArrayList<JarArtifact> jars = new ArrayList<>();
        String dynamicReason = null;
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return new JarSnapshot(List.of(), false, "dependency output is not a directory");
        }
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                String name = child.getFileName().toString();
                if (allowedFiles.contains(name)) {
                    if (!Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                        return new JarSnapshot(List.of(), false, "dependency cache state is not a regular file");
                    }
                    continue;
                }
                if (!name.endsWith(".jar") || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    return new JarSnapshot(List.copyOf(jars), false,
                            "dependency output contains a non-JAR or symbolic entry: " + name);
                }
                jars.add(new JarArtifact(name, Files.size(child), sha256(child)));
                if (name.toUpperCase(Locale.ROOT).contains("SNAPSHOT")) {
                    dynamicReason = "resolved SNAPSHOT dependency requires Maven resolution: " + name;
                }
            }
        }
        return new JarSnapshot(List.copyOf(jars), dynamicReason == null, dynamicReason);
    }

    private void writeState(
            Path staging,
            String fingerprint,
            boolean cacheable,
            String bypassReason,
            List<JarArtifact> jars) throws IOException {
        LinkedHashMap<String, Object> state = new LinkedHashMap<>();
        state.put("schemaVersion", SCHEMA_VERSION);
        state.put("inputFingerprint", fingerprint);
        state.put("cacheable", cacheable);
        if (bypassReason != null) {
            state.put("bypassReason", bypassReason);
        }
        state.put("jars", jars.stream().map(jar -> {
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("name", jar.name());
            item.put("size", jar.size());
            item.put("sha256", jar.sha256());
            return item;
        }).toList());
        Files.writeString(
                staging.resolve(STATE_FILE),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private void addExecutableFingerprint(MessageDigest digest, Path executable) throws IOException {
        Path normalized = executable.toAbsolutePath().normalize();
        update(digest, "maven-executable");
        update(digest, normalized.toString());
        Path real = normalized.toRealPath();
        update(digest, real.toString());
        addFileContent(digest, real);
    }

    private void addFileFingerprint(MessageDigest digest, Path file) throws IOException {
        update(digest, "file");
        update(digest, file.toAbsolutePath().normalize().toString());
        addFileContent(digest, file);
    }

    private void addFileContent(MessageDigest digest, Path file) throws IOException {
        update(digest, Long.toString(Files.size(file)));
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
    }

    private String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private Path environmentPath(String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void recoverInterruptedPublish(Path output, Path previous, BuildLogger logger) throws IOException {
        if (Files.notExists(previous, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.notExists(output, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(output.getParent());
            moveDirectory(previous, output);
            logger.info("[app-dependencies] restored dependency cache after interrupted publish");
            return;
        }
        delete(previous);
    }

    private void publish(Path staging, Path output, Path previous, BuildLogger logger) throws IOException {
        Files.createDirectories(output.getParent());
        delete(previous);
        boolean hadOutput = Files.exists(output, LinkOption.NOFOLLOW_LINKS);
        if (hadOutput) {
            moveDirectory(output, previous);
        }
        try {
            moveDirectory(staging, output);
        } catch (IOException failure) {
            if (hadOutput && Files.notExists(output, LinkOption.NOFOLLOW_LINKS)
                    && Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    moveDirectory(previous, output);
                } catch (IOException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
            }
            throw failure;
        }
        try {
            delete(previous);
        } catch (IOException cleanupFailure) {
            logger.info("[app-dependencies] previous dependency cleanup deferred: " + cleanupFailure.getMessage());
        }
    }

    private Path previousDirectory(Path output) {
        return output.resolveSibling("." + output.getFileName() + "-previous");
    }

    private void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void delete(Path root) throws IOException {
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(root);
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private boolean isWindows() {
        return java.io.File.separatorChar == '\\';
    }

    private record Fingerprint(String digest, boolean cacheable, String bypassReason) {
    }

    private record CacheValidation(boolean hit, String detail) {
    }

    private record JarArtifact(String name, long size, String sha256) {
    }

    private record JarSnapshot(List<JarArtifact> jars, boolean cacheable, String bypassReason) {
    }

    private record InputFiles(List<Path> paths, String bypassReason) {
    }

    private record DynamicInput(String reason) {
    }

    private record ParentReference(String relativePath, boolean disabled) {
    }
}
