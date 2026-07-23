package com.wiz.build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipException;

import com.wiz.runtime.WorkspaceRuntimePaths;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Content-addressed extraction cache for Spring Boot executable JAR compiler
 * classpaths.
 */
final class BootJarClasspathCache {

    private static final int SCHEMA_VERSION = 1;
    private static final int SOURCE_STABILITY_ATTEMPTS = 3;
    private static final int MAX_EXTRACTED_ENTRIES = 50_000;
    private static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
    private static final long MAX_MANIFEST_BYTES = 16L * 1024 * 1024;
    private static final String MANIFEST_FILE = ".complete.json";
    private static final String STAGING_MARKER = ".next-";
    private static final Pattern CACHE_KEY = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SHA_256 = CACHE_KEY;

    private final CacheRootResolver cacheRootResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    BootJarClasspathCache() {
        this(WorkspaceRuntimePaths::prepareCompilerClasspathCache);
    }

    BootJarClasspathCache(Path cacheRoot) {
        this(workspaceRoot -> prepareFixedCacheRoot(workspaceRoot, cacheRoot));
    }

    BootJarClasspathCache(CacheRootResolver cacheRootResolver) {
        if (cacheRootResolver == null) {
            throw new IllegalArgumentException("Boot JAR cache root resolver is required");
        }
        this.cacheRootResolver = cacheRootResolver;
    }

    Optional<Result> resolve(Path workspaceRoot, Path jar, BuildLogger logger) throws IOException {
        if (workspaceRoot == null || jar == null) {
            throw new IllegalArgumentException("Workspace root and Boot JAR path are required");
        }
        BuildLogger buildLogger = logger == null ? BuildLogger.quiet() : logger;
        Path source;
        try {
            source = jar.toAbsolutePath().normalize().toRealPath();
        } catch (IOException exception) {
            if (Files.notExists(jar, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            throw exception;
        }
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || !hasBootLayout(source)) {
            return Optional.empty();
        }

        Path cacheRoot = prepareCacheRoot(workspaceRoot);
        for (int attempt = 1; attempt <= SOURCE_STABILITY_ATTEMPTS; attempt++) {
            SourceFingerprint before = stableSourceFingerprint(source);
            Path target = cacheRoot.resolve(before.digest());
            Validation cached = validate(target, before);
            if (cached.valid()) {
                buildLogger.info("[java-classpath-cache] hit " + before.digest());
                return Optional.of(result(before.digest(), target, cached.manifest()));
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                buildLogger.info("[java-classpath-cache] invalid entry " + before.digest()
                        + ", rebuilding: " + cached.reason());
                deleteTree(target);
            } else {
                buildLogger.info("[java-classpath-cache] miss " + before.digest());
            }

            Path staging = cacheRoot.resolve("." + before.digest() + STAGING_MARKER + UUID.randomUUID());
            try {
                Files.createDirectory(staging);
                Extracted extracted = extract(source, staging, before);
                SourceFingerprint after = stableSourceFingerprint(source);
                if (!before.equals(after)) {
                    buildLogger.info("[java-classpath-cache] source changed during extraction, retrying");
                    continue;
                }
                if (!extracted.bootLayout()) {
                    return Optional.empty();
                }
                writeManifestLast(staging, extracted.manifest());
                Validation staged = validate(staging, before);
                if (!staged.valid()) {
                    throw new IOException("Boot JAR cache staging validation failed: " + staged.reason());
                }

                if (!publish(staging, target)) {
                    Validation winner = validate(target, before);
                    if (winner.valid()) {
                        return Optional.of(result(before.digest(), target, winner.manifest()));
                    }
                    deleteTree(target);
                    if (!publish(staging, target)) {
                        throw new IOException("Boot JAR cache could not publish content-addressed entry "
                                + before.digest());
                    }
                }
                Validation published = validate(target, before);
                if (!published.valid()) {
                    deleteTree(target);
                    throw new IOException("Published Boot JAR cache failed validation: " + published.reason());
                }
                buildLogger.info("[java-classpath-cache] stored " + before.digest()
                        + " files=" + published.manifest().files().size());
                return Optional.of(result(before.digest(), target, published.manifest()));
            } finally {
                deleteTree(staging);
            }
        }
        throw new IOException("Boot JAR changed repeatedly while preparing the compiler classpath: " + source);
    }

    void prune(Path workspaceRoot, Set<String> activeKeys, BuildLogger logger) throws IOException {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("Workspace root is required");
        }
        Set<String> retained = activeKeys == null ? Set.of() : Set.copyOf(activeKeys);
        for (String key : retained) {
            if (key == null || !CACHE_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("Invalid Boot JAR cache key: " + key);
            }
        }
        BuildLogger buildLogger = logger == null ? BuildLogger.quiet() : logger;
        Path cacheRoot = prepareCacheRoot(workspaceRoot);
        try (Stream<Path> children = Files.list(cacheRoot)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                boolean staleEntry = CACHE_KEY.matcher(name).matches() && !retained.contains(name);
                boolean abandonedStaging = name.startsWith(".") && name.contains(STAGING_MARKER);
                if (staleEntry || abandonedStaging) {
                    deleteTree(child);
                    buildLogger.info("[java-classpath-cache] pruned " + name);
                }
            }
        }
    }

    private Path prepareCacheRoot(Path workspaceRoot) throws IOException {
        Path normalizedWorkspace = workspaceRoot.toAbsolutePath().normalize();
        Path cacheRoot = cacheRootResolver.resolve(normalizedWorkspace).toAbsolutePath().normalize();
        WorkspaceRuntimePaths.requireOutsideWorkspace(normalizedWorkspace, cacheRoot);
        Files.createDirectories(cacheRoot);
        if (!Files.isDirectory(cacheRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Boot JAR cache root is not a directory or is a symbolic link: " + cacheRoot);
        }
        return cacheRoot;
    }

    private static Path prepareFixedCacheRoot(Path workspaceRoot, Path cacheRoot) throws IOException {
        if (cacheRoot == null) {
            throw new IllegalArgumentException("Boot JAR cache root is required");
        }
        Path normalized = cacheRoot.toAbsolutePath().normalize();
        WorkspaceRuntimePaths.requireOutsideWorkspace(workspaceRoot, normalized);
        Files.createDirectories(normalized);
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Boot JAR cache root is not a directory or is a symbolic link: " + normalized);
        }
        return normalized;
    }

    private boolean hasBootLayout(Path source) throws IOException {
        try (JarFile jar = new JarFile(source.toFile())) {
            if (jar.getEntry("BOOT-INF/classes/") != null
                    || jar.getEntry("BOOT-INF/lib/") != null
                    || jar.getEntry("BOOT-INF/classpath.idx") != null) {
                return true;
            }
            var manifest = jar.getManifest();
            if (manifest != null) {
                var attributes = manifest.getMainAttributes();
                String mainClass = attributes.getValue("Main-Class");
                if (attributes.getValue("Spring-Boot-Version") == null
                        && (mainClass == null || !mainClass.startsWith("org.springframework.boot.loader."))) {
                    return false;
                }
            }
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("BOOT-INF/classes/")
                        || (name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar"))) {
                    return true;
                }
            }
            return false;
        } catch (ZipException exception) {
            return false;
        }
    }

    private Extracted extract(Path source, Path staging, SourceFingerprint fingerprint) throws IOException {
        Path classes = staging.resolve("classes");
        Path libraries = staging.resolve("lib");
        Files.createDirectories(classes);
        Files.createDirectories(libraries);

        TargetRegistry targets = new TargetRegistry();
        targets.reserveRootDirectory("classes");
        targets.reserveRootDirectory("lib");
        ExtractionBudget budget = new ExtractionBudget();
        ArrayList<CachedFile> files = new ArrayList<>();
        ArrayList<String> classpath = new ArrayList<>();
        classpath.add("classes");
        boolean bootLayout = false;

        try (JarFile jar = new JarFile(source.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (entryName.equals("BOOT-INF/classpath.idx")) {
                    bootLayout = true;
                    continue;
                }

                String rootName;
                String relativeName;
                boolean library;
                if (entryName.startsWith("BOOT-INF/classes/")) {
                    bootLayout = true;
                    rootName = "classes";
                    relativeName = entryName.substring("BOOT-INF/classes/".length());
                    library = false;
                } else if (entryName.startsWith("BOOT-INF/lib/") && entryName.endsWith(".jar")) {
                    bootLayout = true;
                    rootName = "lib";
                    relativeName = entryName.substring("BOOT-INF/lib/".length());
                    library = true;
                } else {
                    continue;
                }

                if (relativeName.isEmpty()) {
                    continue;
                }
                boolean directory = entry.isDirectory() || relativeName.endsWith("/");
                String normalizedRelative = normalizeArchivePath(relativeName, directory);
                String cacheRelative = rootName + "/" + normalizedRelative;
                Path target = resolveCachePath(staging, cacheRelative);
                budget.startEntry(entry);
                if (directory) {
                    targets.reserveDirectory(cacheRelative);
                    continue;
                }

                targets.reserveFile(cacheRelative);
                Files.createDirectories(target.getParent());
                MessageDigest digest = newSha256();
                long size;
                try (InputStream input = jar.getInputStream(entry);
                        OutputStream output = Files.newOutputStream(
                                target,
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE)) {
                    size = copyBounded(input, output, digest, budget);
                }
                files.add(new CachedFile(cacheRelative, size, HexFormat.of().formatHex(digest.digest())));
                if (library) {
                    classpath.add(cacheRelative);
                }
            }
        }

        files.sort(Comparator.comparing(CachedFile::path));
        CacheManifest manifest = new CacheManifest(
                SCHEMA_VERSION,
                fingerprint.digest(),
                fingerprint.size(),
                List.copyOf(classpath),
                List.copyOf(files));
        return new Extracted(bootLayout, manifest);
    }

    private long copyBounded(
            InputStream input,
            OutputStream output,
            MessageDigest digest,
            ExtractionBudget budget) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long entryBytes = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            entryBytes += read;
            budget.addBytes(read, entryBytes);
            output.write(buffer, 0, read);
            digest.update(buffer, 0, read);
        }
        return entryBytes;
    }

    private Validation validate(Path root, SourceFingerprint source) {
        try {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                return Validation.invalid("cache entry is not a directory");
            }
            Path manifestPath = root.resolve(MANIFEST_FILE);
            if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
                return Validation.invalid("completion manifest is missing");
            }
            long manifestSize = Files.size(manifestPath);
            if (manifestSize <= 0 || manifestSize > MAX_MANIFEST_BYTES) {
                return Validation.invalid("completion manifest size is invalid");
            }
            CacheManifest manifest = readManifest(manifestPath);
            if (manifest.schemaVersion() != SCHEMA_VERSION) {
                return Validation.invalid("unsupported manifest schema");
            }
            if (!source.digest().equals(manifest.sourceDigest()) || source.size() != manifest.sourceSize()) {
                return Validation.invalid("source fingerprint does not match");
            }
            String structuralError = validateManifestStructure(manifest);
            if (structuralError != null) {
                return Validation.invalid(structuralError);
            }

            LinkedHashSet<String> expectedFiles = new LinkedHashSet<>();
            expectedFiles.add(MANIFEST_FILE);
            LinkedHashSet<String> expectedDirectories = new LinkedHashSet<>(List.of("classes", "lib"));
            for (CachedFile file : manifest.files()) {
                Path candidate = resolveCachePath(root, file.path());
                if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    return Validation.invalid("cached file is missing or unsafe: " + file.path());
                }
                if (Files.size(candidate) != file.size()) {
                    return Validation.invalid("cached file size changed: " + file.path());
                }
                if (!file.sha256().equals(sha256(candidate))) {
                    return Validation.invalid("cached file digest changed: " + file.path());
                }
                expectedFiles.add(file.path());
                addParentDirectories(file.path(), expectedDirectories);
            }

            LinkedHashSet<String> actualFiles = new LinkedHashSet<>();
            LinkedHashSet<String> actualDirectories = new LinkedHashSet<>();
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path item : paths.toList()) {
                    if (item.equals(root)) {
                        continue;
                    }
                    String relative = portablePath(root.relativize(item));
                    if (Files.isSymbolicLink(item)) {
                        return Validation.invalid("symbolic link found in cache: " + relative);
                    }
                    if (Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS)) {
                        actualDirectories.add(relative);
                    } else if (Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS)) {
                        actualFiles.add(relative);
                    } else {
                        return Validation.invalid("unsupported filesystem entry in cache: " + relative);
                    }
                }
            }
            if (!actualFiles.equals(expectedFiles)) {
                return Validation.invalid("cached file set does not match manifest");
            }
            if (!actualDirectories.equals(expectedDirectories)) {
                return Validation.invalid("cached directory set does not match manifest");
            }
            return Validation.valid(manifest);
        } catch (IOException | RuntimeException exception) {
            return Validation.invalid(exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage());
        }
    }

    private String validateManifestStructure(CacheManifest manifest) {
        if (!SHA_256.matcher(manifest.sourceDigest()).matches() || manifest.sourceSize() < 0) {
            return "invalid source metadata";
        }
        if (manifest.files().size() > MAX_EXTRACTED_ENTRIES || manifest.classpath().isEmpty()
                || !"classes".equals(manifest.classpath().get(0))) {
            return "invalid manifest entry counts or classpath root";
        }

        LinkedHashSet<String> filePaths = new LinkedHashSet<>();
        LinkedHashSet<String> libraryPaths = new LinkedHashSet<>();
        long totalSize = 0;
        for (CachedFile file : manifest.files()) {
            if (file == null || file.path() == null || file.sha256() == null) {
                return "manifest contains a null file entry";
            }
            try {
                String normalized = normalizeArchivePath(file.path(), false);
                if (!normalized.equals(file.path())
                        || !(normalized.startsWith("classes/") || normalized.startsWith("lib/"))) {
                    return "manifest contains an unsafe file path";
                }
                if (normalized.startsWith("lib/") && !normalized.endsWith(".jar")) {
                    return "manifest contains a non-JAR library";
                }
            } catch (IOException exception) {
                return exception.getMessage();
            }
            if (!filePaths.add(file.path()) || file.size() < 0 || file.size() > MAX_ENTRY_BYTES
                    || !SHA_256.matcher(file.sha256()).matches()) {
                return "manifest contains invalid or duplicate file metadata";
            }
            totalSize += file.size();
            if (totalSize > MAX_TOTAL_BYTES) {
                return "manifest exceeds cache size limit";
            }
            if (file.path().startsWith("lib/")) {
                libraryPaths.add(file.path());
            }
        }

        LinkedHashSet<String> classpath = new LinkedHashSet<>();
        for (int index = 0; index < manifest.classpath().size(); index++) {
            String entry = manifest.classpath().get(index);
            if (entry == null || !classpath.add(entry)) {
                return "manifest contains an invalid or duplicate classpath entry";
            }
            if (index == 0) {
                continue;
            }
            try {
                String normalized = normalizeArchivePath(entry, false);
                if (!normalized.equals(entry) || !normalized.startsWith("lib/") || !normalized.endsWith(".jar")) {
                    return "manifest contains an unsafe library classpath entry";
                }
            } catch (IOException exception) {
                return exception.getMessage();
            }
            if (!libraryPaths.contains(entry)) {
                return "classpath references a library not present in the manifest";
            }
        }
        if (!classpath.containsAll(libraryPaths) || classpath.size() != libraryPaths.size() + 1) {
            return "manifest library classpath is incomplete";
        }
        return null;
    }

    private CacheManifest readManifest(Path manifestPath) throws IOException {
        Map<String, Object> value = objectMapper.readValue(
                Files.readAllBytes(manifestPath),
                new TypeReference<LinkedHashMap<String, Object>>() {
                });
        int schemaVersion = intValue(value.get("schemaVersion"), "schemaVersion");
        String sourceDigest = stringValue(value.get("sourceDigest"), "sourceDigest");
        long sourceSize = longValue(value.get("sourceSize"), "sourceSize");
        List<String> classpath = stringList(value.get("classpath"), "classpath");
        Object rawFiles = value.get("files");
        if (!(rawFiles instanceof List<?> values)) {
            throw new IOException("Boot JAR cache manifest files must be an array");
        }
        ArrayList<CachedFile> files = new ArrayList<>();
        for (Object rawFile : values) {
            if (!(rawFile instanceof Map<?, ?> file)) {
                throw new IOException("Boot JAR cache manifest file entry must be an object");
            }
            files.add(new CachedFile(
                    stringValue(file.get("path"), "files.path"),
                    longValue(file.get("size"), "files.size"),
                    stringValue(file.get("sha256"), "files.sha256")));
        }
        return new CacheManifest(schemaVersion, sourceDigest, sourceSize, List.copyOf(classpath), List.copyOf(files));
    }

    private void writeManifestLast(Path staging, CacheManifest manifest) throws IOException {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", manifest.schemaVersion());
        value.put("sourceDigest", manifest.sourceDigest());
        value.put("sourceSize", manifest.sourceSize());
        value.put("classpath", manifest.classpath());
        ArrayList<Map<String, Object>> files = new ArrayList<>();
        for (CachedFile file : manifest.files()) {
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("path", file.path());
            item.put("size", file.size());
            item.put("sha256", file.sha256());
            files.add(item);
        }
        value.put("files", files);

        Path manifestPath = staging.resolve(MANIFEST_FILE);
        Path temporary = staging.resolve(MANIFEST_FILE + ".tmp-" + UUID.randomUUID());
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
            Files.writeString(temporary, json, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            moveAtomically(temporary, manifestPath);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Result result(String key, Path root, CacheManifest manifest) throws IOException {
        ArrayList<Path> entries = new ArrayList<>();
        for (String entry : manifest.classpath()) {
            entries.add(resolveCachePath(root, entry).toAbsolutePath().normalize());
        }
        return new Result(key, entries);
    }

    private boolean publish(Path staging, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            moveAtomically(staging, target);
            return true;
        } catch (FileAlreadyExistsException | DirectoryNotEmptyException exception) {
            return false;
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private SourceFingerprint stableSourceFingerprint(Path source) throws IOException {
        for (int attempt = 1; attempt <= SOURCE_STABILITY_ATTEMPTS; attempt++) {
            BasicFileAttributes before = Files.readAttributes(
                    source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String digest = sha256(source);
            BasicFileAttributes after = Files.readAttributes(
                    source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (sameFileState(before, after)) {
                return new SourceFingerprint(digest, after.size());
            }
        }
        throw new IOException("Boot JAR changed while calculating its cache key: " + source);
    }

    private boolean sameFileState(BasicFileAttributes left, BasicFileAttributes right) {
        return left.isRegularFile()
                && right.isRegularFile()
                && left.size() == right.size()
                && left.lastModifiedTime().equals(right.lastModifiedTime())
                && java.util.Objects.equals(left.fileKey(), right.fileKey());
    }

    private String sha256(Path file) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String normalizeArchivePath(String name, boolean directory) throws IOException {
        if (name == null || name.isBlank() || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0
                || name.startsWith("/")) {
            throw new IOException("Boot JAR contains an unsafe cache path: " + name);
        }
        String candidate = directory && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (candidate.isEmpty()) {
            throw new IOException("Boot JAR contains an empty cache path");
        }
        String[] segments = candidate.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("Boot JAR contains an unsafe cache path: " + name);
            }
        }
        try {
            Path normalized = Path.of(segments[0], java.util.Arrays.copyOfRange(segments, 1, segments.length)).normalize();
            if (normalized.isAbsolute() || normalized.getNameCount() != segments.length) {
                throw new IOException("Boot JAR contains an unsafe cache path: " + name);
            }
        } catch (InvalidPathException exception) {
            throw new IOException("Boot JAR contains an invalid cache path: " + name, exception);
        }
        return String.join("/", segments);
    }

    private Path resolveCachePath(Path root, String relative) throws IOException {
        String normalized = normalizeArchivePath(relative, false);
        Path target;
        try {
            String[] segments = normalized.split("/");
            target = root.resolve(Path.of(segments[0], java.util.Arrays.copyOfRange(segments, 1, segments.length)))
                    .normalize();
        } catch (InvalidPathException exception) {
            throw new IOException("Boot JAR cache path is invalid: " + relative, exception);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot) || normalizedTarget.equals(normalizedRoot)) {
            throw new IOException("Boot JAR cache path escapes its root: " + relative);
        }
        return normalizedTarget;
    }

    private void addParentDirectories(String file, Set<String> directories) {
        int separator = file.lastIndexOf('/');
        while (separator > 0) {
            String parent = file.substring(0, separator);
            directories.add(parent);
            separator = parent.lastIndexOf('/');
        }
    }

    private String portablePath(Path path) {
        StringBuilder value = new StringBuilder();
        for (Path segment : path) {
            if (!value.isEmpty()) {
                value.append('/');
            }
            value.append(segment);
        }
        return value.toString();
    }

    private int intValue(Object value, String name) throws IOException {
        long number = longValue(value, name);
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IOException("Boot JAR cache manifest " + name + " is outside integer range");
        }
        return (int) number;
    }

    private long longValue(Object value, String name) throws IOException {
        if (!(value instanceof Number number)) {
            throw new IOException("Boot JAR cache manifest " + name + " must be a number");
        }
        return number.longValue();
    }

    private String stringValue(Object value, String name) throws IOException {
        if (!(value instanceof String text)) {
            throw new IOException("Boot JAR cache manifest " + name + " must be a string");
        }
        return text;
    }

    private List<String> stringList(Object value, String name) throws IOException {
        if (!(value instanceof List<?> values)) {
            throw new IOException("Boot JAR cache manifest " + name + " must be an array");
        }
        ArrayList<String> result = new ArrayList<>();
        for (Object item : values) {
            result.add(stringValue(item, name));
        }
        return List.copyOf(result);
    }

    private void deleteTree(Path path) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    @FunctionalInterface
    interface CacheRootResolver {
        Path resolve(Path workspaceRoot) throws IOException;
    }

    record Result(String key, List<Path> entries) {
        Result {
            entries = List.copyOf(entries);
        }

        String cacheKey() {
            return key;
        }

        List<Path> classpathEntries() {
            return entries;
        }
    }

    private record SourceFingerprint(String digest, long size) {
    }

    private record CachedFile(String path, long size, String sha256) {
    }

    private record CacheManifest(
            int schemaVersion,
            String sourceDigest,
            long sourceSize,
            List<String> classpath,
            List<CachedFile> files) {
    }

    private record Extracted(boolean bootLayout, CacheManifest manifest) {
    }

    private record Validation(boolean valid, CacheManifest manifest, String reason) {
        private static Validation valid(CacheManifest manifest) {
            return new Validation(true, manifest, "");
        }

        private static Validation invalid(String reason) {
            return new Validation(false, null, reason == null || reason.isBlank() ? "unknown validation error" : reason);
        }
    }

    private static final class ExtractionBudget {
        private int entries;
        private long totalBytes;

        private void startEntry(JarEntry entry) throws IOException {
            entries++;
            if (entries > MAX_EXTRACTED_ENTRIES) {
                throw new IOException("Boot JAR exceeds the compiler cache entry limit");
            }
            if (entry.getSize() > MAX_ENTRY_BYTES) {
                throw new IOException("Boot JAR entry exceeds the compiler cache size limit: " + entry.getName());
            }
        }

        private void addBytes(int count, long entryBytes) throws IOException {
            totalBytes += count;
            if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                throw new IOException("Boot JAR exceeds the compiler cache extraction size limit");
            }
        }
    }

    private static final class TargetRegistry {
        private final Set<String> entryTargets = new HashSet<>();
        private final Set<String> files = new HashSet<>();
        private final Set<String> directories = new HashSet<>();

        private void reserveRootDirectory(String path) {
            directories.add(path);
        }

        private void reserveDirectory(String path) throws IOException {
            if (!entryTargets.add(path)) {
                throw new IOException("Boot JAR contains a duplicate extraction target: " + path);
            }
            if (files.contains(path)) {
                throw new IOException("Boot JAR contains a file/directory extraction collision: " + path);
            }
            requireParentsAreDirectories(path);
            directories.add(path);
        }

        private void reserveFile(String path) throws IOException {
            if (!entryTargets.add(path)) {
                throw new IOException("Boot JAR contains a duplicate extraction target: " + path);
            }
            if (files.contains(path) || directories.contains(path)) {
                throw new IOException("Boot JAR contains a file/directory extraction collision: " + path);
            }
            requireParentsAreDirectories(path);
            files.add(path);
        }

        private void requireParentsAreDirectories(String path) throws IOException {
            int separator = path.lastIndexOf('/');
            while (separator > 0) {
                String parent = path.substring(0, separator);
                if (files.contains(parent)) {
                    throw new IOException("Boot JAR contains a file/directory extraction collision: " + path);
                }
                directories.add(parent);
                separator = parent.lastIndexOf('/');
            }
        }
    }
}
