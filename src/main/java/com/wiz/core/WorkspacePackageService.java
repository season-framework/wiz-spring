package com.wiz.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;

/** Applies a Java package-root change before rebuilding a workspace. */
public class WorkspacePackageService {

    private static final Set<String> REWRITABLE_EXTENSIONS = Set.of(
            ".java", ".json", ".properties", ".xml", ".yaml", ".yml");
    private static final String JAVA_NAME = "[A-Za-z_$][A-Za-z0-9_$]*";
    private static final String JAVA_QUALIFIED_NAME = JAVA_NAME + "(?:\\." + JAVA_NAME + ")*";
    private static final Pattern JAVA_PACKAGE_OR_IMPORT = Pattern.compile(
            "(?m)^\\s*(package|import)\\s+(?:static\\s+)?(" + JAVA_QUALIFIED_NAME + ")");
    private static final Pattern YAML_PACKAGE_ROOT = Pattern.compile(
            "(?m)^\\s*package(?:-root|Root)\\s*:\\s*['\\\"]?(" + JAVA_QUALIFIED_NAME + ")");
    private static final Pattern PROPERTIES_PACKAGE_ROOT = Pattern.compile(
            "(?m)^\\s*wiz\\.java\\.package(?:-root|Root)\\s*=\\s*(" + JAVA_QUALIFIED_NAME + ")");
    private static final List<Pattern> STRONG_WIZ_PACKAGE_PATTERNS = List.of(
            Pattern.compile("^(.+?)\\.module\\." + JAVA_NAME
                    + "\\.(?:application\\.(?:model|service)|domain\\.entity|infrastructure\\.orm|security)(?:\\.|$)"),
            Pattern.compile("^(.+?)\\.portal\\." + JAVA_NAME
                    + "\\.model(?:\\.(?:struct|db|orm|security))?(?:\\.|$)"),
            Pattern.compile("^(.+?)\\.(?:web\\.(?:api|route)|realtime\\.socket|application\\.(?:model|service)"
                    + "|domain\\.entity|infrastructure\\.orm|security\\.(?:guard|auth|session))(?:\\.|$)"),
            Pattern.compile("^(.+?)\\.model\\.(?:struct|db|orm|security)(?:\\.|$)"));
    private static final List<Pattern> DECLARED_WIZ_PACKAGE_PATTERNS = List.of(
            Pattern.compile("^(.+?)\\.(?:api|socket|route|controller|auth|session)(?:\\.|$)"),
            Pattern.compile("^(.+?)\\.model(?:\\.|$)"));

    public PackageSelection selectForBuild(PathService paths, String requestedPackageRoot) throws IOException {
        String currentPackageRoot = paths.packageRoot();
        if (requestedPackageRoot == null) {
            return new PackageSelection(paths.workspaceContext(currentPackageRoot), false);
        }

        String selectedPackageRoot = paths.validatePackageRoot(requestedPackageRoot);
        if (selectedPackageRoot.equals(currentPackageRoot)) {
            return new PackageSelection(paths.workspaceContext(selectedPackageRoot), false);
        }

        rewritePackageReferences(paths, currentPackageRoot, selectedPackageRoot);
        ensurePackageConfigured(paths, selectedPackageRoot);
        return new PackageSelection(paths.workspaceContext(selectedPackageRoot), true);
    }

    /**
     * Rebase package references copied from another WIZ workspace to the package
     * selected by {@code create --package}. Imported application config is often
     * gitignored, so Java declarations and imports are also used to discover the
     * previous package root.
     */
    public boolean normalizeImportedPackageReferences(PathService paths, String selectedPackageRoot) throws IOException {
        String targetPackageRoot = paths.validatePackageRoot(selectedPackageRoot);
        Optional<String> importedPackageRoot = configuredImportedPackageRoot(paths, targetPackageRoot);
        if (importedPackageRoot.isEmpty()) {
            importedPackageRoot = inferredImportedPackageRoot(paths, targetPackageRoot);
        }
        if (importedPackageRoot.isEmpty()) {
            return false;
        }
        rewritePackageReferences(paths, importedPackageRoot.get(), targetPackageRoot);
        return true;
    }

    private Optional<String> configuredImportedPackageRoot(PathService paths, String targetPackageRoot) throws IOException {
        Map<String, Integer> candidates = new LinkedHashMap<>();
        Path configRoot = paths.configRoot();
        if (!Files.isDirectory(configRoot, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.walk(configRoot)) {
            for (Path file : files.filter(this::isRewritableFile).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                collectPackageRoots(source, YAML_PACKAGE_ROOT, candidates, targetPackageRoot);
                collectPackageRoots(source, PROPERTIES_PACKAGE_ROOT, candidates, targetPackageRoot);
            }
        }
        return preferredPackageRoot(candidates);
    }

    private Optional<String> inferredImportedPackageRoot(PathService paths, String targetPackageRoot) throws IOException {
        Map<String, Integer> candidates = new LinkedHashMap<>();
        Path sourceRoot = paths.root().resolve("src");
        if (!Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java"))
                    .toList()) {
                Matcher references = JAVA_PACKAGE_OR_IMPORT.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (references.find()) {
                    boolean declaration = references.group(1).equals("package");
                    inferredPackageRoot(references.group(2), declaration)
                            .filter(candidate -> !candidate.equals(targetPackageRoot))
                            .ifPresent(candidate -> candidates.merge(candidate, 1, Integer::sum));
                }
            }
        }
        return preferredPackageRoot(candidates);
    }

    private void collectPackageRoots(
            String source,
            Pattern pattern,
            Map<String, Integer> candidates,
            String targetPackageRoot) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (validPackageRoot(candidate) && !candidate.equals(targetPackageRoot)) {
                candidates.merge(candidate, 1, Integer::sum);
            }
        }
    }

    private Optional<String> inferredPackageRoot(String qualifiedName, boolean declaration) {
        for (Pattern pattern : STRONG_WIZ_PACKAGE_PATTERNS) {
            Matcher matcher = pattern.matcher(qualifiedName);
            if (matcher.find() && validPackageRoot(matcher.group(1))) {
                return Optional.of(matcher.group(1));
            }
        }
        if (declaration) {
            for (Pattern pattern : DECLARED_WIZ_PACKAGE_PATTERNS) {
                Matcher matcher = pattern.matcher(qualifiedName);
                if (matcher.find() && validPackageRoot(matcher.group(1))) {
                    return Optional.of(matcher.group(1));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> preferredPackageRoot(Map<String, Integer> candidates) {
        return candidates.entrySet().stream()
                .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparingInt(entry -> entry.getKey().length())
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey);
    }

    private boolean validPackageRoot(String value) {
        return value != null
                && value.matches(JAVA_QUALIFIED_NAME)
                && !value.equals("java")
                && !value.startsWith("java.");
    }

    private void rewritePackageReferences(PathService paths, String currentPackageRoot, String selectedPackageRoot) throws IOException {
        String selectedSuffix = selectedPackageRoot.startsWith(currentPackageRoot + ".")
                ? selectedPackageRoot.substring(currentPackageRoot.length())
                : null;
        String selectedReferenceGuard = selectedSuffix == null
                ? ""
                : "(?!" + Pattern.quote(selectedSuffix) + "(?![A-Za-z0-9_$]))";
        Pattern reference = Pattern.compile("(?<![A-Za-z0-9_$])"
                + Pattern.quote(currentPackageRoot)
                + selectedReferenceGuard
                + "(?![A-Za-z0-9_$])");
        rewriteFile(paths.root().resolve("pom.xml"), reference, selectedPackageRoot);
        rewriteDirectory(paths.configRoot(), reference, selectedPackageRoot);
        rewriteDirectory(paths.root().resolve("src"), reference, selectedPackageRoot);
    }

    private void rewriteDirectory(Path root, Pattern reference, String selectedPackageRoot) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(this::isRewritableFile).toList()) {
                rewriteFile(file, reference, selectedPackageRoot);
            }
        }
    }

    private boolean isRewritableFile(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return REWRITABLE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private void rewriteFile(Path file, Pattern reference, String selectedPackageRoot) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        String source = Files.readString(file, StandardCharsets.UTF_8);
        String rewritten = reference.matcher(source).replaceAll(Matcher.quoteReplacement(selectedPackageRoot));
        if (!source.equals(rewritten)) {
            Files.writeString(file, rewritten, StandardCharsets.UTF_8);
        }
    }

    private void ensurePackageConfigured(PathService paths, String selectedPackageRoot) throws IOException {
        if (selectedPackageRoot.equals(paths.packageRoot())) {
            return;
        }

        Path config = firstApplicationConfig(paths.configRoot());
        writePackageSetting(config, selectedPackageRoot);
        if (!selectedPackageRoot.equals(paths.packageRoot())) {
            throw new IllegalStateException("Failed to persist wiz.java.package-root in workspace configuration");
        }
    }

    private Path firstApplicationConfig(Path configRoot) {
        for (String name : List.of("application.yml", "application.yaml")) {
            Path candidate = configRoot.resolve(name);
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        return configRoot.resolve("application.yml");
    }

    private void writePackageSetting(Path config, String selectedPackageRoot) throws IOException {
        Files.createDirectories(config.getParent());
        if (!Files.exists(config)) {
            Files.writeString(config, packageBlock(selectedPackageRoot), StandardCharsets.UTF_8);
            return;
        }

        List<String> lines = new ArrayList<>(Files.readAllLines(config, StandardCharsets.UTF_8));
        int wizLine = findBlock(lines, 0, lines.size(), "wiz", -1);
        if (wizLine < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            lines.add("wiz:");
            lines.add("  java:");
            lines.add("    package-root: " + selectedPackageRoot);
            writeLines(config, lines);
            return;
        }

        int wizIndent = indentation(lines.get(wizLine));
        int wizEnd = blockEnd(lines, wizLine, wizIndent);
        int javaLine = findBlock(lines, wizLine + 1, wizEnd, "java", wizIndent);
        if (javaLine < 0) {
            String childIndent = " ".repeat(wizIndent + 2);
            lines.add(wizLine + 1, childIndent + "java:");
            lines.add(wizLine + 2, childIndent + "  package-root: " + selectedPackageRoot);
            writeLines(config, lines);
            return;
        }

        int javaIndent = indentation(lines.get(javaLine));
        int javaEnd = blockEnd(lines, javaLine, javaIndent);
        for (int index = javaLine + 1; index < javaEnd; index++) {
            String content = content(lines.get(index));
            if (indentation(lines.get(index)) > javaIndent
                    && (content.startsWith("package-root:") || content.startsWith("packageRoot:"))) {
                lines.set(index, " ".repeat(indentation(lines.get(index))) + "package-root: " + selectedPackageRoot);
                writeLines(config, lines);
                return;
            }
        }

        lines.add(javaLine + 1, " ".repeat(javaIndent + 2) + "package-root: " + selectedPackageRoot);
        writeLines(config, lines);
    }

    private int findBlock(List<String> lines, int start, int end, String key, int parentIndent) {
        for (int index = start; index < end; index++) {
            String line = lines.get(index);
            if (significant(line)
                    && indentation(line) > parentIndent
                    && content(line).equals(key + ":")) {
                return index;
            }
        }
        return -1;
    }

    private int blockEnd(List<String> lines, int blockLine, int blockIndent) {
        for (int index = blockLine + 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (significant(line) && indentation(line) <= blockIndent) {
                return index;
            }
        }
        return lines.size();
    }

    private boolean significant(String line) {
        String stripped = line.stripLeading();
        return !stripped.isBlank() && !stripped.startsWith("#");
    }

    private String content(String line) {
        String stripped = line.strip();
        int comment = stripped.indexOf('#');
        return (comment < 0 ? stripped : stripped.substring(0, comment)).stripTrailing();
    }

    private int indentation(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private void writeLines(Path config, List<String> lines) throws IOException {
        Files.writeString(config, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private String packageBlock(String selectedPackageRoot) {
        return "wiz:\n"
                + "  java:\n"
                + "    package-root: " + selectedPackageRoot + "\n";
    }

    public record PackageSelection(ProjectContext context, boolean changed) {
    }
}
