package com.wiz.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MavenDependencyCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void reusesVerifiedDependenciesWhenInputsAreUnchanged() throws Exception {
        ProjectContext project = workspace("stable", minimalPom("1.0.0"));
        FakeCommandExecutor executor = new FakeCommandExecutor();
        CapturingLogger logger = new CapturingLogger();
        MavenDependencyCache cache = cache(executor);

        cache.resolve(project, logger);
        Path state = ProjectBuildLayout.dependencyRoot(project).resolve(MavenDependencyCache.STATE_FILE);
        assertTrue(Files.isRegularFile(state));
        assertEquals(1, executor.calls());

        cache.resolve(project, logger);

        assertEquals(1, executor.calls());
        assertTrue(logger.messages().stream().anyMatch(message -> message.contains("cache hit")));
    }

    @Test
    void invalidatesForLocalParentProjectConfigUserSettingsAndWrapperChanges() throws Exception {
        Path parent = tempDir.resolve("parent");
        Files.createDirectories(parent);
        Path parentPom = parent.resolve("pom.xml");
        Files.writeString(parentPom, minimalPom("1.0.0"));
        Path workspace = parent.resolve("child");
        ProjectContext project = workspaceAt(workspace, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>test</groupId>
                    <artifactId>fixture</artifactId>
                    <version>1.0.0</version>
                    <relativePath>../pom.xml</relativePath>
                  </parent>
                  <artifactId>child</artifactId>
                </project>
                """);
        FakeCommandExecutor executor = new FakeCommandExecutor();
        MavenDependencyCache cache = cache(executor);

        cache.resolve(project, BuildLogger.quiet());
        cache.resolve(project, BuildLogger.quiet());
        assertEquals(1, executor.calls());

        Files.writeString(parentPom, minimalPom("1.0.1"));
        cache.resolve(project, BuildLogger.quiet());
        assertEquals(2, executor.calls());

        Path mavenConfig = workspace.resolve(".mvn/maven.config");
        Files.createDirectories(mavenConfig.getParent());
        Files.writeString(mavenConfig, "--offline\n");
        cache.resolve(project, BuildLogger.quiet());
        assertEquals(3, executor.calls());

        Path settings = tempDir.resolve("home/.m2/settings.xml");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "<settings><!-- SNAPSHOT policy documentation --></settings>\n");
        cache.resolve(project, BuildLogger.quiet());
        assertEquals(4, executor.calls());
        cache.resolve(project, BuildLogger.quiet());
        assertEquals(4, executor.calls(), "non-project Maven settings text must not disable caching");

        Path wrapper = workspace.resolve("mvnw");
        Files.writeString(wrapper, "#!/bin/sh\n# changed wrapper\nexit 0\n");
        wrapper.toFile().setExecutable(true, false);
        cache.resolve(project, BuildLogger.quiet());
        assertEquals(5, executor.calls());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<version>1.0-SNAPSHOT</version>",
            "<dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId><version>[1,2)</version></dependency></dependencies>",
            "<properties><dep.version>[1,2)</dep.version></properties><dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId><version>${dep.version}</version></dependency></dependencies>",
            "<properties><dep.version>RELEASE</dep.version></properties><dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId><version>${dep.version}</version></dependency></dependencies>",
            "<profiles><profile><id>local</id><activation><activeByDefault>true</activeByDefault></activation></profile></profiles>",
            "<dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId><version>1</version><scope>system</scope><systemPath>/tmp/y.jar</systemPath></dependency></dependencies>"
    })
    void conservativelyBypassesDynamicMavenModels(String fragment) throws Exception {
        ProjectContext project = workspace("dynamic-" + Integer.toUnsignedString(fragment.hashCode()), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId>
                  <artifactId>dynamic</artifactId>
                """ + fragment + "\n</project>\n");
        FakeCommandExecutor executor = new FakeCommandExecutor();
        MavenDependencyCache cache = cache(executor);

        cache.resolve(project, BuildLogger.quiet());
        cache.resolve(project, BuildLogger.quiet());

        assertEquals(2, executor.calls());
    }

    @Test
    void bypassesDynamicVersionInjectedByUserSettings() throws Exception {
        ProjectContext project = workspace("settings-dynamic", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId>
                  <artifactId>settings-dynamic</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>x</groupId>
                      <artifactId>y</artifactId>
                      <version>${dep.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Path settings = tempDir.resolve("home/.m2/settings.xml");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, """
                <settings>
                  <profiles>
                    <profile>
                      <id>dynamic</id>
                      <properties>
                        <dep.version>[1,2)</dep.version>
                      </properties>
                    </profile>
                  </profiles>
                  <activeProfiles>
                    <activeProfile>dynamic</activeProfile>
                  </activeProfiles>
                </settings>
                """);
        FakeCommandExecutor executor = new FakeCommandExecutor();
        MavenDependencyCache cache = cache(executor);

        cache.resolve(project, BuildLogger.quiet());
        cache.resolve(project, BuildLogger.quiet());

        assertEquals(2, executor.calls());
    }

    @Test
    void bypassesUserSettingsProfileActivation() throws Exception {
        ProjectContext project = workspace("settings-activation", minimalPom("1.0.0"));
        Path settings = tempDir.resolve("home/.m2/settings.xml");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, """
                <settings>
                  <profiles>
                    <profile>
                      <id>external-file</id>
                      <activation>
                        <file>
                          <exists>${user.home}/activate-review-profile</exists>
                        </file>
                      </activation>
                    </profile>
                  </profiles>
                </settings>
                """);
        FakeCommandExecutor executor = new FakeCommandExecutor();
        MavenDependencyCache cache = cache(executor);

        cache.resolve(project, BuildLogger.quiet());
        cache.resolve(project, BuildLogger.quiet());

        assertEquals(2, executor.calls());
    }

    @Test
    void bypassesDynamicVersionInjectedByMavenOptions() throws Exception {
        ProjectContext project = workspace("maven-options-dynamic", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId>
                  <artifactId>maven-options-dynamic</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>x</groupId>
                      <artifactId>y</artifactId>
                      <version>${dep.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        FakeCommandExecutor executor = new FakeCommandExecutor();
        MavenDependencyCache cache = new MavenDependencyCache(executor, Map.of(
                "HOME", tempDir.resolve("home").toString(),
                "PATH", "/usr/bin:/bin",
                "MAVEN_OPTS", "-Ddep.version='[1,2)'"));

        cache.resolve(project, BuildLogger.quiet());
        cache.resolve(project, BuildLogger.quiet());

        assertEquals(2, executor.calls());
    }

    @Test
    void bypassesWhenResolvedGraphContainsTransitiveSnapshot() throws Exception {
        ProjectContext project = workspace("transitive-snapshot", minimalPom("1.0.0"));
        FakeCommandExecutor executor = new FakeCommandExecutor();
        executor.jarName("transitive-2.0-SNAPSHOT.jar");
        MavenDependencyCache cache = cache(executor);

        cache.resolve(project, BuildLogger.quiet());
        cache.resolve(project, BuildLogger.quiet());

        assertEquals(2, executor.calls());
    }

    @Test
    void reResolvesForTamperedMissingExtraAndCorruptState() throws Exception {
        ProjectContext project = workspace("integrity", minimalPom("1.0.0"));
        FakeCommandExecutor executor = new FakeCommandExecutor();
        MavenDependencyCache cache = cache(executor);
        Path dependencies = ProjectBuildLayout.dependencyRoot(project);
        Path jar = dependencies.resolve("fixture-1.0.jar");

        cache.resolve(project, BuildLogger.quiet());
        byte[] original = Files.readAllBytes(jar);
        byte[] tampered = original.clone();
        tampered[tampered.length - 1] ^= 1;
        Files.write(jar, tampered);
        cache.resolve(project, BuildLogger.quiet());
        assertEquals(2, executor.calls());

        Files.writeString(dependencies.resolve("extra.jar"), "extra");
        cache.resolve(project, BuildLogger.quiet());
        assertEquals(3, executor.calls());

        Files.delete(jar);
        cache.resolve(project, BuildLogger.quiet());
        assertEquals(4, executor.calls());

        Files.writeString(dependencies.resolve(MavenDependencyCache.STATE_FILE), "{invalid-json");
        cache.resolve(project, BuildLogger.quiet());
        assertEquals(5, executor.calls());
        assertTrue(Files.isRegularFile(jar));
    }

    @Test
    void failedRefreshPreservesPreviouslyPublishedDependenciesAndState() throws Exception {
        ProjectContext project = workspace("failure", minimalPom("1.0.0"));
        FakeCommandExecutor executor = new FakeCommandExecutor();
        MavenDependencyCache cache = cache(executor);
        Path dependencies = ProjectBuildLayout.dependencyRoot(project);
        Path jar = dependencies.resolve("fixture-1.0.jar");
        Path state = dependencies.resolve(MavenDependencyCache.STATE_FILE);

        cache.resolve(project, BuildLogger.quiet());
        byte[] oldJar = Files.readAllBytes(jar);
        byte[] oldState = Files.readAllBytes(state);
        Files.writeString(project.root().resolve("pom.xml"), minimalPom("2.0.0"));
        executor.failNext();

        IOException failure = assertThrows(IOException.class,
                () -> cache.resolve(project, BuildLogger.quiet()));

        assertTrue(failure.getMessage().contains("resolution failed"));
        assertArrayEquals(oldJar, Files.readAllBytes(jar));
        assertArrayEquals(oldState, Files.readAllBytes(state));
        assertTrue(Files.notExists(ProjectBuildLayout.dependencyStagingRoot(project)));
    }

    @Test
    void cacheHitStillRequiresAnExecutableMavenWrapper() throws Exception {
        ProjectContext project = workspace("required-maven", minimalPom("1.0.0"));
        FakeCommandExecutor executor = new FakeCommandExecutor();
        MavenDependencyCache cache = cache(executor);

        cache.resolve(project, BuildLogger.quiet());
        Path wrapper = project.root().resolve("mvnw");
        wrapper.toFile().setExecutable(false, false);

        assertThrows(IllegalStateException.class,
                () -> cache.resolve(project, BuildLogger.quiet()));
        assertEquals(1, executor.calls());
    }

    @Test
    void restoresInterruptedPublishedDirectoryBeforeCacheValidation() throws Exception {
        ProjectContext project = workspace("recovery", minimalPom("1.0.0"));
        FakeCommandExecutor executor = new FakeCommandExecutor();
        MavenDependencyCache cache = cache(executor);
        Path output = ProjectBuildLayout.dependencyRoot(project);
        Path previous = output.resolveSibling("." + output.getFileName() + "-previous");

        cache.resolve(project, BuildLogger.quiet());
        Files.move(output, previous);
        cache.resolve(project, BuildLogger.quiet());

        assertEquals(1, executor.calls());
        assertTrue(Files.isDirectory(output));
        assertTrue(Files.notExists(previous));
    }

    @Test
    void missingPomRemovesPublishedAndStagedDependencyState() throws Exception {
        ProjectContext project = workspace("removed-pom", minimalPom("1.0.0"));
        FakeCommandExecutor executor = new FakeCommandExecutor();
        MavenDependencyCache cache = cache(executor);

        cache.resolve(project, BuildLogger.quiet());
        Files.createDirectories(ProjectBuildLayout.dependencyStagingRoot(project));
        Files.delete(project.root().resolve("pom.xml"));
        cache.resolve(project, BuildLogger.quiet());

        assertEquals(1, executor.calls());
        assertTrue(Files.notExists(ProjectBuildLayout.dependencyRoot(project)));
        assertTrue(Files.notExists(ProjectBuildLayout.dependencyStagingRoot(project)));
    }

    private MavenDependencyCache cache(FakeCommandExecutor executor) {
        return new MavenDependencyCache(executor, Map.of(
                "HOME", tempDir.resolve("home").toString(),
                "PATH", "/usr/bin:/bin",
                "MAVEN_OPTS", ""));
    }

    private ProjectContext workspace(String name, String pom) throws Exception {
        return workspaceAt(tempDir.resolve(name), pom);
    }

    private ProjectContext workspaceAt(Path root, String pom) throws Exception {
        Files.createDirectories(root.resolve("src/app"));
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("pom.xml"), pom);
        Path wrapper = root.resolve("mvnw");
        Files.writeString(wrapper, "#!/bin/sh\nexit 0\n");
        wrapper.toFile().setExecutable(true, false);
        return new PathService(root).workspaceContext();
    }

    private String minimalPom(String version) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId>
                  <artifactId>fixture</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(version);
    }

    private static final class FakeCommandExecutor extends CommandExecutor {
        private int calls;
        private boolean failNext;
        private String jarName = "fixture-1.0.jar";

        @Override
        public CommandResult run(
                String phase,
                Path workspaceRoot,
                Path cwd,
                List<String> argv,
                Duration timeout,
                int outputCapBytes,
                BuildLogger logger) throws IOException {
            calls++;
            if (failNext) {
                failNext = false;
                return new CommandResult(phase, argv, cwd, 17, 1, false, false, "simulated Maven failure");
            }
            Path output = argv.stream()
                    .filter(value -> value.startsWith("-DoutputDirectory="))
                    .map(value -> Path.of(value.substring("-DoutputDirectory=".length())))
                    .findFirst()
                    .orElseThrow();
            Files.createDirectories(output);
            Files.writeString(output.resolve(jarName), "resolved-dependency-" + calls + "\n");
            return new CommandResult(phase, argv, cwd, 0, 1, false, false, "");
        }

        int calls() {
            return calls;
        }

        void failNext() {
            failNext = true;
        }

        void jarName(String jarName) {
            this.jarName = jarName;
        }
    }

    private static final class CapturingLogger implements BuildLogger {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void info(String message) {
            messages.add(message);
        }

        @Override
        public void output(String text) {
        }

        List<String> messages() {
            return List.copyOf(messages);
        }
    }
}
