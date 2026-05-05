package com.wiz.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;
import com.wiz.security.GitUriPolicy;
import com.wiz.security.SecretMasker;

import org.springframework.stereotype.Service;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

@Service
public class ProjectService {

    private static final Set<String> EXPORT_EXCLUDED_DIRECTORIES = Set.of(".git", "build", "bundle", "node_modules", ".angular");
    private static final Set<String> EXPORT_EXCLUDED_FILES = Set.of();
    private static final String EMBEDDED_JAVA_SAMPLE_ROOT = "/wiz/templates/default-project-java/";
    private static final String EMBEDDED_JAVA_SAMPLE_FILES = "/wiz/templates/default-project-java.files";

    private final PathService paths;

    public ProjectService(PathService paths) {
        this.paths = paths;
    }

    public ProjectContext createProject(String name, String uri, Path sourcePath) throws IOException, InterruptedException {
        return createProject(name, uri, sourcePath, false);
    }

    public ProjectContext createProject(String name, String uri, Path sourcePath, boolean ignoredGenerateJavaStubs) throws IOException, InterruptedException {
        if (uri != null && !uri.isBlank() && sourcePath != null) {
            throw new IllegalArgumentException("Use either --uri or --path, not both");
        }

        ProjectContext project = paths.projectContext(name);
        if (Files.exists(project.root())) {
            throw new IllegalArgumentException("Project already exists: " + project.name());
        }
        Files.createDirectories(paths.projectsRoot());

        if (uri != null && !uri.isBlank()) {
            cloneProject(uri, project.root());
        } else if (sourcePath != null) {
            importSource(sourcePath.toAbsolutePath().normalize(), project.root());
        } else {
            createDefaultProject(project);
        }

        ensureProjectDirectories(project);
        return project;
    }

    private void importSource(Path sourcePath, Path target) throws IOException {
        if (Files.isRegularFile(sourcePath) && isZipProjectSource(sourcePath)) {
            new ZipProjectSource().extract(sourcePath, target);
            return;
        }
        copyDirectory(sourcePath, target);
    }

    private boolean isZipProjectSource(Path sourcePath) {
        String filename = sourcePath.getFileName() == null ? "" : sourcePath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return filename.endsWith(".wizproject") || filename.endsWith(".zip");
    }

    public List<String> listProjects() throws IOException {
        if (!Files.isDirectory(paths.projectsRoot())) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(paths.projectsRoot())) {
            return children
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    public void deleteProject(String name) throws IOException {
        ProjectContext project = paths.projectContext(name);
        if (!Files.isDirectory(project.root())) {
            throw new IllegalArgumentException("Project does not exist: " + project.name());
        }
        delete(project.root());
    }

    public Path exportProject(String name, Path output) throws IOException {
        ProjectContext project = paths.projectContext(name);
        if (!Files.isDirectory(project.root())) {
            throw new IllegalArgumentException("Project does not exist: " + project.name());
        }
        Path archive = exportOutput(project, output);
        Files.createDirectories(archive.getParent());
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(archive)) {
            try (Stream<Path> paths = Files.walk(project.root())) {
                for (Path file : paths
                        .filter(path -> !path.equals(project.root()))
                        .filter(path -> shouldExport(project.root(), path))
                        .filter(Files::isRegularFile)
                        .sorted()
                        .toList()) {
                    addZipEntry(project.root(), file, zip);
                }
            }
        }
        return archive;
    }

    private Path exportOutput(ProjectContext project, Path output) {
        Path archive = output == null
                ? paths.root().resolve(project.name() + ".wizproject")
                : output;
        archive = archive.toAbsolutePath().normalize();
        if (Files.isDirectory(archive)) {
            archive = archive.resolve(project.name() + ".wizproject");
        }
        String filename = archive.getFileName() == null ? "" : archive.getFileName().toString();
        if (!filename.endsWith(".wizproject")) {
            archive = archive.resolveSibling(filename + ".wizproject");
        }
        return archive;
    }

    private boolean shouldExport(Path projectRoot, Path path) {
        Path relative = projectRoot.relativize(path);
        for (Path part : relative) {
            if (EXPORT_EXCLUDED_DIRECTORIES.contains(part.toString())) {
                return false;
            }
        }
        return !EXPORT_EXCLUDED_FILES.contains(relative.getFileName().toString());
    }

    private void addZipEntry(Path projectRoot, Path file, ZipArchiveOutputStream zip) throws IOException {
        if (Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException("Symbolic links are not allowed in project exports: " + projectRoot.relativize(file));
        }
        Path relative = projectRoot.relativize(file);
        String name = relative.toString().replace(java.io.File.separatorChar, '/');
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setSize(Files.size(file));
        zip.putArchiveEntry(entry);
        Files.copy(file, zip);
        zip.closeArchiveEntry();
    }

    private void createDefaultProject(ProjectContext project) throws IOException {
        if (copyEmbeddedJavaTemplate(project)) {
            rewriteTemplateProjectPackage(project);
            return;
        }

        ensureProjectDirectories(project);
        Path app = project.appRoot().resolve("page.dashboard");
        Files.createDirectories(app);
        writeIfMissing(project.root().resolve("README.md"), "# WIZ Java Project\n\nGenerated by wiz-java.\n");
        writeIfMissing(project.root().resolve("pom.xml"), minimalProjectPom(project));
        writeIfMissing(project.root().resolve("package.json"), "{\n  \"type\": \"module\",\n  \"scripts\": {\n    \"build\": \"echo build placeholder\"\n  }\n}\n");
        writeIfMissing(project.configRoot().resolve("season.yml"), "auth_baseuri: /auth\n");
        writeIfMissing(project.assetsRoot().resolve("sample.txt"), "WIZ Java asset\n");
        writeIfMissing(project.modelRoot().resolve("README.md"), "Project model and struct Java sources live here.\n");
        writeIfMissing(project.routeRoot().resolve("README.md"), "Project route handlers live here.\n");
        writeIfMissing(project.sourceRoot().resolve("portal/season/portal.json"), "{\"id\":\"season\",\"runtime\":\"java\"}\n");
        writeIfMissing(app.resolve("app.json"), dashboardAppJson(project));
        writeIfMissing(app.resolve("view.pug"), "section\n  h1 WIZ Java Dashboard\n  p Generated Spring WIZ project\n");
        writeIfMissing(app.resolve("view.html"), "<main id=\"wiz-app\"><h1>WIZ Java Dashboard</h1><pre data-wiz-output>ready</pre></main>\n");
        writeIfMissing(app.resolve("view.ts"), "document.querySelector('[data-wiz-output]')?.replaceChildren('page.dashboard ready');\n");
        writeIfMissing(app.resolve("api.java"), dashboardApiJava());
    }

    private void ensureProjectDirectories(ProjectContext project) throws IOException {
        Files.createDirectories(project.sourceRoot());
        Files.createDirectories(project.appRoot());
        Files.createDirectories(project.sourceRoot().resolve("controller"));
        Files.createDirectories(project.assetsRoot());
        Files.createDirectories(project.modelRoot());
        Files.createDirectories(project.routeRoot());
        Files.createDirectories(project.sourceRoot().resolve("portal"));
        Files.createDirectories(project.configRoot());
    }

    private String dashboardAppJson(ProjectContext project) {
        String handler = ProjectJavaNaming.appApiHandlerClass(project.name(), "page.dashboard");
        return "{\n"
                + "  \"id\": \"page.dashboard\",\n"
                + "  \"mode\": \"page\",\n"
                + "  \"title\": \"dashboard\",\n"
                + "  \"viewuri\": \"/dashboard\",\n"
                + "  \"controller\": \"\",\n"
                + "  \"template\": \"wiz-page-dashboard()\",\n"
                + "  \"runtime\": \"java\",\n"
                + "  \"api\": {\n"
                + "    \"handler\": \"" + handler + "\",\n"
                + "    \"functions\": [\"overview\"]\n"
                + "  }\n"
                + "}\n";
    }

    private String minimalProjectPom(ProjectContext project) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n"
                + "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
                + "    <modelVersion>4.0.0</modelVersion>\n"
                + "    <groupId>com.wiz.project</groupId>\n"
                + "    <artifactId>wiz-project-" + ProjectJavaNaming.packageSegment(project.name()) + "</artifactId>\n"
                + "    <version>0.0.1-SNAPSHOT</version>\n"
                + "    <properties>\n"
                + "        <java.version>21</java.version>\n"
                + "    </properties>\n"
                + "    <dependencies>\n"
                + "        <!-- Add project-specific Java dependencies here. -->\n"
                + "    </dependencies>\n"
                + "</project>\n";
    }

    private String dashboardApiJava() {
        return "import java.util.Map;\n\n"
                + "public final class PageDashboardApi {\n"
                + "    public Object overview(Object wiz) {\n"
                + "        return Map.of(\"message\", \"Java WIZ project ready\");\n"
                + "    }\n"
                + "}\n";
    }

    private boolean copyEmbeddedJavaTemplate(ProjectContext project) throws IOException {
        try (InputStream manifest = ProjectService.class.getResourceAsStream(EMBEDDED_JAVA_SAMPLE_FILES)) {
            if (manifest == null) {
                return false;
            }
            for (String entry : new String(manifest.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (!entry.isBlank()) {
                    copyEmbeddedJavaTemplateFile(project, entry.trim());
                }
            }
            return true;
        }
    }

    private void copyEmbeddedJavaTemplateFile(ProjectContext project, String entry) throws IOException {
        if (entry.contains("\\") || entry.startsWith("/") || entry.contains("..")) {
            throw new IllegalArgumentException("Unsupported embedded template entry: " + entry);
        }
        Path target = project.root().resolve(entry).normalize();
        if (!target.startsWith(project.root().normalize())) {
            throw new IllegalArgumentException("Embedded template entry escapes project root: " + entry);
        }
        try (InputStream input = ProjectService.class.getResourceAsStream(EMBEDDED_JAVA_SAMPLE_ROOT + entry)) {
            if (input == null) {
                throw new IOException("Embedded template entry is missing: " + entry);
            }
            Files.createDirectories(target.getParent());
            Files.copy(input, target);
        }
    }

    private void rewriteTemplateProjectPackage(ProjectContext project) throws IOException {
        String packageRoot = ProjectJavaNaming.packageRoot(project.name());
        try (Stream<Path> paths = Files.walk(project.root())) {
            for (Path file : paths.filter(path -> Files.isRegularFile(path) && rewritableTemplateFile(path)).toList()) {
                String source = Files.readString(file);
                String rewritten = source.replace("com.wiz.project.main", packageRoot);
                if (!source.equals(rewritten)) {
                    Files.writeString(file, rewritten);
                }
            }
        }
    }

    private boolean rewritableTemplateFile(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".json");
    }

    private void cloneProject(String uri, Path target) throws IOException, InterruptedException {
        String validatedUri = GitUriPolicy.validate(uri);
        Path cloneTarget = target.toAbsolutePath().normalize();
        if (!cloneTarget.startsWith(paths.projectsRoot().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Project clone destination escapes workspace projects directory");
        }
        Process process = new ProcessBuilder("git", "clone", validatedUri, cloneTarget.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("git clone failed with exit code " + exitCode + System.lineSeparator() + SecretMasker.mask(output));
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("Source path must be a directory: " + source);
        }
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path item : paths.toList()) {
                Path relative = source.relativize(item);
                Path destination = target.resolve(relative.toString()).normalize();
                if (!destination.startsWith(target.normalize())) {
                    throw new IllegalArgumentException("Project copy escapes target directory");
                }
                if (Files.isSymbolicLink(item)) {
                    throw new IllegalArgumentException("Symbolic links are not allowed in project copies: " + relative);
                }
                if (relative.toString().contains(".git" + java.io.File.separator) || relative.toString().equals(".git")) {
                    continue;
                }
                if (Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(item, destination);
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

    private void writeIfMissing(Path path, String content) throws IOException {
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        }
    }
}
