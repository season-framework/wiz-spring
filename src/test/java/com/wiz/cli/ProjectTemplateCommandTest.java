package com.wiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wiz.core.FrontendTemplate;

import picocli.CommandLine;

class ProjectTemplateCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void createDefaultsToAngularWiz() {
        CreateCommand create = new CreateCommand();
        new CommandLine(create).parseArgs(
                tempDir.resolve("default-template").toString(),
                "--package", "com.example.demo");

        assertEquals(com.wiz.core.FrontendTemplate.ANGULAR_WIZ, create.selectedTemplate());
    }

    @Test
    void createStopsBeforeWritingWhenTheToolchainCheckFails() {
        Path target = tempDir.resolve("toolchain-failure");
        CreateCommand create = new CreateCommand(() -> {
            throw new IllegalStateException("Node.js is too old");
        });
        CommandLine command = new CommandLine(create);
        command.setExecutionExceptionHandler((exception, ignored, parseResult) -> 1);

        assertEquals(1, command.execute(
                target.toString(), "--package", "com.example.toolchain"));
        assertFalse(Files.exists(target));
    }

    @Test
    void defaultCreatePublishesTheVendoredAngularWizBuilder() throws Exception {
        Path target = tempDir.resolve("default-angular-wiz");
        StringWriter output = new StringWriter();
        CommandLine command = commandLine();
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute(
                "create", target.toString(), "--package", "com.example.defaultapp"));

        String packageJson = Files.readString(target.resolve("package.json"));
        assertTrue(packageJson.contains("\"frontend\": \"angular-wiz\""));
        assertTrue(packageJson.contains("\"wizbuild\""));
        assertTrue(packageJson.contains("\"bundle\""));
        assertTrue(Files.isRegularFile(target.resolve("scripts/wizbuild.mjs")));
        assertTrue(Files.isRegularFile(target.resolve("scripts/wizwatch.mjs")));
        assertTrue(Files.isRegularFile(target.resolve(".mvn/wrapper/maven-wrapper.properties")));
        assertTrue(Files.isExecutable(target.resolve("mvnw")));
        if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
            assertTrue(Files.getPosixFilePermissions(target.resolve("mvnw"))
                    .containsAll(java.util.Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_EXECUTE,
                            PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_EXECUTE)));
        }
        assertTrue(Files.isRegularFile(target.resolve("mvnw.cmd")));
        assertTrue(Files.readString(target.resolve(".mvn/wrapper/maven-wrapper.properties"))
                .contains("apache-maven-3.9.15-bin.zip"));
        assertTrue(Files.readString(target.resolve(".github/copilot-instructions.md"))
                .contains("AGENTS.md"));
        assertFalse(packageJson.contains("@season-framework/wiz-frontend"));
        assertFalse(Files.exists(target.resolve(".wiz")));
        String readme = Files.readString(target.resolve("README.md"));
        assertTrue(readme.contains("npm ci"));
        assertTrue(readme.contains("created with\n`--uri` or `--path`, run `npm install` once"));
        assertTrue(output.toString().contains("Frontend template: angular-wiz"));
        assertTrue(output.toString().contains("npm run wizbuild"));
    }

    @Test
    void everyListedTemplateCanBeCreatedFromItsEmbeddedOverlay() throws Exception {
        for (com.wiz.core.FrontendTemplate template : com.wiz.core.FrontendTemplate.values()) {
            Path target = tempDir.resolve("template-" + template.id());
            CommandLine command = commandLine();
            command.setOut(new PrintWriter(new StringWriter()));

            assertEquals(0, command.execute(
                    "create",
                    target.toString(),
                    "--package", "com.example." + template.id().replace('-', '_'),
                    "--template", template.id()), template.id());

            assertTrue(Files.isRegularFile(target.resolve("package.json")), template.id());
            assertTrue(Files.isRegularFile(target.resolve("pom.xml")), template.id());
            String packageJson = Files.readString(target.resolve("package.json"));
            String packageLock = Files.readString(target.resolve("package-lock.json"));
            String pom = Files.readString(target.resolve("pom.xml"));
            assertTrue(packageJson.contains("\"version\": \"1.1.0\""), template.id());
            assertTrue(packageJson.contains("\"frontend\": \"" + template.id() + "\""), template.id());
            assertTrue(packageJson.contains("\"node\": \"^22.22.3 || ^24.15.0 || ^26.0.0\""), template.id());
            assertTrue(packageJson.contains("\"npm\": \">=10.0.0\""), template.id());
            assertTrue(packageLock.contains("\"node\": \"^22.22.3 || ^24.15.0 || ^26.0.0\""), template.id());
            assertTrue(packageLock.contains("\"npm\": \">=10.0.0\""), template.id());
            assertTrue(pom.contains("<version>1.1.0</version>"), template.id());
            String archiveType = template == com.wiz.core.FrontendTemplate.JSP ? "war" : "jar";
            assertTrue(Files.readString(target.resolve("docker-compose.yaml"))
                    .contains("${APP_ARTIFACT:-application." + archiveType + "}"), template.id());
            assertTrue(Files.readString(target.resolve("deploy/.env.example"))
                    .contains("APP_ARTIFACT=application." + archiveType), template.id());
            assertTrue(Files.readString(target.resolve("deploy/docker/backend.Dockerfile"))
                    .contains("ARG APP_ARTIFACT=application." + archiveType), template.id());
            assertFalse(Files.exists(target.resolve(".wiz")), template.id());
            assertFalse(Files.exists(target.resolve("config/wiz.yml")), template.id());
            assertTrue(Files.readString(target.resolve(".github/copilot-instructions.md"))
                    .contains("docs/ai/frontend.md"), template.id());
        }
    }

    @Test
    void normalizesDotsOnlyForTheDockerComposeProjectName() throws Exception {
        Path target = tempDir.resolve("sample.app");
        CommandLine command = commandLine();
        command.setOut(new PrintWriter(new StringWriter()));

        assertEquals(0, command.execute(
                "create", target.toString(),
                "--package", "com.example.sample",
                "--template", "html"));

        assertTrue(Files.readString(target.resolve("pom.xml"))
                .contains("<artifactId>sample.app</artifactId>"));
        assertTrue(Files.readString(target.resolve("package.json"))
                .contains("\"name\": \"sample.app\""));
        assertTrue(Files.readString(target.resolve("docker-compose.yaml"))
                .startsWith("name: sample-app\n"));
    }

    @Test
    void jspProxyOverlayServesOnlyAssetsAndProxiesTheRenderedApplication() throws Exception {
        Path target = tempDir.resolve("jsp-proxy-overlay");
        CommandLine command = commandLine();
        command.setOut(new PrintWriter(new StringWriter()));

        assertEquals(0, command.execute(
                "create",
                target.toString(),
                "--package", "com.example.jspproxy",
                "--template", "jsp"));

        String nginx = Files.readString(target.resolve("deploy/nginx/default.conf.template"));
        assertTrue(nginx.contains("location ^~ /assets/"));
        assertTrue(nginx.contains("alias /usr/share/nginx/html/"));
        assertTrue(nginx.contains("location / {"));
        assertTrue(nginx.contains("proxy_pass http://${BACKEND_HOST}:${BACKEND_PORT}"));
        assertFalse(nginx.contains("try_files $uri $uri/ /index.html"));

        String apache = Files.readString(target.resolve("deploy/apache2/000-default.conf.template"));
        assertTrue(apache.contains("Alias \"/assets/\" \"/usr/local/apache2/htdocs/\""));
        assertTrue(apache.contains("ProxyPass \"/assets/\" \"!\""));
        assertTrue(apache.contains("ProxyPass \"/\" \"http://${BACKEND_HOST}:${BACKEND_PORT}/\""));
        assertFalse(apache.contains("RewriteRule ^ /index.html"));
    }

    @Test
    void listsAllSupportedTemplatesAndMarksDefault() {
        StringWriter output = new StringWriter();
        CommandLine command = commandLine();
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("templates"));

        String templates = output.toString();
        assertTrue(templates.contains("html"));
        assertTrue(templates.contains("jsp"));
        assertTrue(templates.contains("angular-wiz"));
        assertTrue(templates.contains("angular"));
        assertTrue(templates.contains("react"));
        assertTrue(templates.contains("angular-wiz") && templates.contains("(default)"));
    }

    @Test
    void createRejectsAnUnsupportedTemplateBeforeWritingAnything() {
        StringWriter error = new StringWriter();
        CommandLine command = commandLine();
        command.setErr(new PrintWriter(error));
        Path target = tempDir.resolve("invalid-template");

        assertEquals(2, command.execute(
                "create", target.toString(), "--package", "com.example.demo", "--template", "vue"));

        assertFalse(Files.exists(target));
        assertTrue(error.toString().contains("Unsupported frontend template"));
    }

    @Test
    void createsAStandaloneHtmlProjectWithoutHiddenRuntimeMetadata() throws Exception {
        StringWriter output = new StringWriter();
        CommandLine command = commandLine();
        command.setOut(new PrintWriter(output));
        Path target = tempDir.resolve("standalone-html");

        assertEquals(0, command.execute(
                "create",
                target.toString(),
                "--package", "com.example.standalone",
                "--template", "html"));

        assertTrue(Files.isRegularFile(target.resolve("package.json")));
        assertTrue(Files.readString(target.resolve("package.json")).contains("\"frontend\": \"html\""));
        assertTrue(Files.isRegularFile(
                target.resolve("src/main/java/com/example/standalone/Application.java")));
        assertFalse(Files.exists(target.resolve(".wiz")));
        assertFalse(Files.exists(target.resolve("config/wiz.yml")));
        assertFalse(Files.exists(target.resolve(".codex/config.toml")));
        assertFalse(Files.readString(target.resolve("package.json"))
                .contains("@season-framework/wiz-frontend"));
        assertTrue(output.toString().contains("npm run bundle"));
    }

    @Test
    void importedProjectRequiresExplicitTemplate() throws Exception {
        Path source = tempDir.resolve("source");
        Files.createDirectories(source);
        Path target = tempDir.resolve("target");
        StringWriter error = new StringWriter();
        CommandLine command = commandLine();
        command.setErr(new PrintWriter(error));

        assertEquals(2, command.execute(
                "create", target.toString(), "--package", "com.example.demo", "--path", source.toString()));

        assertFalse(Files.exists(target));
        assertTrue(error.toString().contains("--template must be specified explicitly"));
    }

    @Test
    void importedProjectMergesManagedPackageScriptsAndUsesNpmInstallGuidance() throws Exception {
        Path source = tempDir.resolve("merge-source");
        Files.createDirectories(source);
        Files.writeString(source.resolve("package.json"), """
                {
                  "name": "existing-app",
                  "scripts": {
                    "build": "webpack --mode production",
                    "dev": "webpack serve",
                    "test": "node --test"
                  },
                  "dependencies": {
                    "existing-library": "1.2.3"
                  }
                }
                """);
        Files.writeString(source.resolve("package-lock.json"), "{\"lockfileVersion\":3}\n");
        Files.writeString(source.resolve("user-source.txt"), "keep me\n");
        writeCompatibleFrontend(source, FrontendTemplate.HTML);
        Path target = tempDir.resolve("merge-target");
        StringWriter output = new StringWriter();
        CommandLine command = commandLine();
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute(
                "create",
                target.toString(),
                "--package", "com.example.imported",
                "--template", "html",
                "--path", source.toString()));

        String packageJson = Files.readString(target.resolve("package.json"));
        assertTrue(packageJson.contains("\"frontend\" : \"html\""));
        assertTrue(packageJson.contains("\"build\" : \"node scripts/build.mjs\""));
        assertTrue(packageJson.contains("\"dev\" : \"node scripts/dev.mjs\""));
        assertFalse(packageJson.contains("original:build"));
        assertFalse(packageJson.contains("original:dev"));
        assertTrue(packageJson.contains("\"test\" : \"node --test\""));
        assertTrue(packageJson.contains("\"existing-library\" : \"1.2.3\""));
        assertEquals("{\"lockfileVersion\":3}\n", Files.readString(target.resolve("package-lock.json")));
        assertEquals("keep me\n", Files.readString(target.resolve("user-source.txt")));
        assertTrue(output.toString().contains("npm install"));
        assertFalse(output.toString().contains("npm ci"));
    }

    @Test
    void importedFrontendBuildConfigsAndPomAreReplacedAndArchivedForSelectedTemplate() throws Exception {
        Map<String, List<String>> managedConfigs = Map.of(
                "angular-wiz", List.of(
                        "angular.json", "src/angular/tsconfig.json", "src/angular/tsconfig.app.json"),
                "angular", List.of(
                        "angular.json", "tsconfig.json", "tsconfig.app.json", "proxy.conf.cjs"),
                "react", List.of("vite.config.js"));

        for (Map.Entry<String, List<String>> template : managedConfigs.entrySet()) {
            Path source = tempDir.resolve("managed-config-source-" + template.getKey());
            Files.createDirectories(source);
            Files.writeString(source.resolve("package.json"), "{}\n");
            Files.writeString(source.resolve("pom.xml"), "original Maven build\n");
            writeCompatibleFrontend(source, FrontendTemplate.fromId(template.getKey()));
            for (String relative : template.getValue()) {
                Path config = source.resolve(relative);
                Files.createDirectories(config.getParent());
                Files.writeString(config, "original frontend build: " + relative + "\n");
            }

            Path target = tempDir.resolve("managed-config-target-" + template.getKey());
            CommandLine command = commandLine();
            command.setOut(new PrintWriter(new StringWriter()));
            assertEquals(0, command.execute(
                    "create", target.toString(),
                    "--package", "com.example." + template.getKey().replace('-', '_'),
                    "--template", template.getKey(),
                    "--path", source.toString()), template.getKey());

            assertFalse(Files.readString(target.resolve("pom.xml")).contains("original Maven build"));
            assertEquals("original Maven build\n", Files.readString(
                    target.resolve("replaced-originals/wiz-spring-import/pom.xml")));
            assertEquals("original Maven build\n", Files.readString(source.resolve("pom.xml")));
            for (String relative : template.getValue()) {
                assertFalse(Files.readString(target.resolve(relative)).contains("original frontend build"),
                        template.getKey() + ": " + relative);
                assertEquals("original frontend build: " + relative + "\n", Files.readString(
                        target.resolve("replaced-originals/wiz-spring-import").resolve(relative)),
                        template.getKey() + ": " + relative);
                assertEquals("original frontend build: " + relative + "\n",
                        Files.readString(source.resolve(relative)), template.getKey() + ": " + relative);
            }
        }
    }

    @Test
    void createHelpDocumentsStandaloneDefaultAndHasNoRuntimeJarOption() {
        StringWriter output = new StringWriter();
        CommandLine command = commandLine();
        command.setOut(new PrintWriter(output));

        assertEquals(0, command.execute("create", "--help"));

        String help = output.toString();
        assertTrue(help.contains("standalone Spring project"));
        assertTrue(help.contains("Default: angular-wiz"));
        assertTrue(help.contains("--template"));
        assertFalse(help.contains("--runtime-jar"));
        assertFalse(help.contains(".codex"));
        assertFalse(help.contains("config/wiz.yml"));
    }

    private CommandLine commandLine() {
        CommandLine.IFactory delegate = CommandLine.defaultFactory();
        return new CommandLine(new WizCommand(), new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                if (type == CreateCommand.class) {
                    CreateCommand create = new CreateCommand(() ->
                            new com.wiz.core.DevelopmentToolchain.Report("21.0.0", "24.15.0", "10.0.0"));
                    return type.cast(create);
                }
                return delegate.create(type);
            }
        });
    }

    private void writeCompatibleFrontend(Path root, FrontendTemplate template) throws Exception {
        switch (template) {
            case ANGULAR_WIZ -> Files.createDirectories(root.resolve("src/app"));
            case ANGULAR -> {
                write(root.resolve("frontend/src/index.html"), "<app-root></app-root>\n");
                write(root.resolve("frontend/src/main.ts"), "export {};\n");
                write(root.resolve("frontend/src/styles.css"), "\n");
            }
            case REACT -> {
                write(root.resolve("frontend/index.html"), "<div id=\"root\"></div>\n");
                Files.createDirectories(root.resolve("frontend/src"));
            }
            case HTML -> write(root.resolve("frontend/index.html"), "<!doctype html><title>import</title>\n");
            case JSP -> Files.createDirectories(root.resolve("src/main/webapp/WEB-INF/jsp"));
        }
    }

    private void write(Path file, String contents) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }
}
