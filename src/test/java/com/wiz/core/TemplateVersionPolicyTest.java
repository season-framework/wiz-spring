package com.wiz.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TemplateVersionPolicyTest {

    private static final String WIZ_VERSION = "1.1.1";
    private static final String JAVA_RELEASE = "25";
    private static final String SPRING_BOOT_VERSION = "4.1.1";
    private static final String SPRING_FRAMEWORK_VERSION = "7.0.9";
    private static final String SPRINGDOC_VERSION = "3.1.0";
    private static final String MAVEN_VERSION = "3.9.15";
    private static final String NODE_LTS_RANGE = "\"node\": \"^22.22.3 || ^24.15.0\"";
    private static final Map<String, String> ANGULAR_RUNTIME = Map.of(
            "@angular/common", "22.1.4",
            "@angular/compiler", "22.1.4",
            "@angular/core", "22.1.4",
            "@angular/forms", "22.1.4",
            "@angular/platform-browser", "22.1.4",
            "@angular/router", "22.1.4");
    private static final Map<String, String> ANGULAR_BUILD = Map.of(
            "@angular/build", "22.1.6",
            "@angular/cli", "22.1.6",
            "@angular/compiler-cli", "22.1.4",
            "typescript", "6.0.3");
    private static final List<String> ANGULAR_INSTALL_SCRIPTS = List.of(
            "@parcel/watcher@2.6.0",
            "esbuild@0.28.2",
            "lmdb@3.5.6",
            "msgpackr-extract@3.0.4");

    @Test
    void everyTemplateUsesTheSameStableBackendAndLtsToolchainPolicy() throws Exception {
        for (FrontendTemplate template : FrontendTemplate.values()) {
            String root = "/wiz/templates/project-" + template.id() + "/";
            String pom = resource(root + "pom.xml");
            String packageJson = resource(root + "package.json");
            String packageLock = resource(root + "package-lock.json");

            assertTrue(pom.contains("<version>4.1.1</version>"), template.id());
            assertTrue(pom.contains("<java.version>25</java.version>"), template.id());
            assertTrue(pom.contains("<springdoc.version>3.1.0</springdoc.version>"), template.id());
            assertTrue(pom.contains("<goal>properties</goal>"), template.id());
            assertTrue(pom.contains("-javaagent:${org.mockito:mockito-core:jar}"), template.id());
            assertTrue(packageJson.contains(NODE_LTS_RANGE), template.id());
            assertTrue(packageLock.contains(NODE_LTS_RANGE), template.id());
            assertFalse(packageJson.contains("^26.0.0"), template.id());
        }
    }

    @Test
    void bothAngularTemplatesStayOnTheValidatedAngular22Matrix() throws Exception {
        for (String template : List.of("angular", "angular-wiz")) {
            String root = "/wiz/templates/project-" + template + "/";
            String packageJson = resource(root + "package.json");
            String packageLock = resource(root + "package-lock.json");

            for (Map.Entry<String, String> dependency : ANGULAR_RUNTIME.entrySet()) {
                assertPinned(packageJson, dependency, template);
                assertPinned(packageLock, dependency, template + " lock");
            }
            for (Map.Entry<String, String> dependency : ANGULAR_BUILD.entrySet()) {
                assertPinned(packageJson, dependency, template);
                assertPinned(packageLock, dependency, template + " lock");
            }
            for (String installScript : ANGULAR_INSTALL_SCRIPTS) {
                assertTrue(packageJson.contains("\"" + installScript + "\": true"),
                        () -> template + " must approve the reviewed install script for " + installScript);
            }
        }

        String wizTsconfig = resource("/wiz/templates/project-angular-wiz/src/angular/tsconfig.json");
        assertFalse(wizTsconfig.contains("\"baseUrl\""));
        assertFalse(wizTsconfig.contains("\"downlevelIteration\""));
        assertTrue(wizTsconfig.contains("[\"./src/libs/*\"]"));
        assertTrue(wizTsconfig.contains("[\"./src/*\"]"));
    }

    @Test
    void apiDocumentationIsExplicitInDevelopmentAndOptInForProduction() throws Exception {
        String application = resource("/wiz/templates/project-common/src/main/resources/application.yml");
        String production = resource("/wiz/templates/project-common/src/main/resources/application-prod.yml");
        String compose = resource("/wiz/templates/project-common/docker-compose.yaml");

        assertTrue(application.contains("enabled: true"));
        assertTrue(production.contains("enabled: ${SPRINGDOC_API_DOCS_ENABLED:false}"));
        assertTrue(production.contains("enabled: ${SPRINGDOC_SWAGGER_UI_ENABLED:false}"));
        assertTrue(compose.contains("SPRINGDOC_API_DOCS_ENABLED: ${SPRINGDOC_API_DOCS_ENABLED:-false}"));
        assertTrue(compose.contains("SPRINGDOC_SWAGGER_UI_ENABLED: ${SPRINGDOC_SWAGGER_UI_ENABLED:-false}"));
    }

    @Test
    void generatedReadmeAndAiInstructionsDeclareTheExecutableVersionPolicy() throws Exception {
        String readme = resource("/wiz/templates/project-common/README.md");
        String agents = resource("/wiz/templates/project-common/AGENTS.md");
        String copilot = resource("/wiz/templates/project-common/.github/copilot-instructions.md");
        String backend = resource("/wiz/templates/project-common/docs/ai/backend-spring.md");
        String deployment = resource("/wiz/templates/project-common/docs/ai/deployment.md");

        for (String document : List.of(readme, agents, copilot, backend)) {
            assertContains(document, "WIZ Spring `" + WIZ_VERSION + "`");
            assertContains(document, "Spring Boot `" + SPRING_BOOT_VERSION + "`");
            assertContains(document, "Spring Framework `" + SPRING_FRAMEWORK_VERSION + "`");
        }
        for (String document : List.of(readme, agents, backend, deployment)) {
            assertContains(document, JAVA_RELEASE);
        }
        for (String document : List.of(readme, agents, copilot, deployment)) {
            assertContains(document, MAVEN_VERSION);
        }
        assertContains(readme, "springdoc `" + SPRINGDOC_VERSION + "`");
        assertContains(agents, "springdoc `" + SPRINGDOC_VERSION + "`");
        assertContains(backend, "springdoc `" + SPRINGDOC_VERSION + "`");
        assertContains(backend, "jakarta.*");
        assertContains(deployment, "^22.22.3 || ^24.15.0");

        for (String document : List.of(readme, agents, copilot, backend, deployment)) {
            assertFalse(document.contains("Java 21"));
            assertFalse(document.contains("Spring Boot `4.0"));
            assertFalse(document.contains("wiz-spring build"));
            assertFalse(document.contains("WizContext"));
        }
    }

    @Test
    void frontendInstructionsNameThePinnedOnePointOnePointOneToolchains() throws Exception {
        Map<String, List<String>> expected = Map.of(
                "angular-wiz", List.of("WIZ Spring `1.1.1`", "22.1.4", "22.1.6", "6.0.3", "3.0.4"),
                "angular", List.of("WIZ Spring `1.1.1`", "22.1.4", "22.1.6", "6.0.3"),
                "react", List.of("WIZ Spring `1.1.1`", "19.2.8", "8.2.2", "6.1.1"),
                "html", List.of("WIZ Spring `1.1.1`", "^22.22.3 || ^24.15.0"),
                "jsp", List.of("WIZ Spring `1.1.1`", "Spring Boot `4.1.1`", "^22.22.3 || ^24.15.0"));

        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            String guide = resource("/wiz/templates/project-" + entry.getKey() + "/docs/ai/frontend.md");
            for (String fragment : entry.getValue()) {
                assertContains(guide, fragment);
            }
            assertFalse(guide.contains("Angular 21"), entry.getKey());
            assertFalse(guide.contains("Spring Boot 4.0"), entry.getKey());
        }
    }

    private void assertPinned(String contents, Map.Entry<String, String> dependency, String source) {
        assertTrue(contents.contains("\"" + dependency.getKey() + "\": \"" + dependency.getValue() + "\""),
                () -> source + " must pin " + dependency.getKey() + " to " + dependency.getValue());
    }

    private void assertContains(String contents, String fragment) {
        assertTrue(contents.contains(fragment), () -> "Missing documentation policy fragment: " + fragment);
    }

    private String resource(String path) throws IOException {
        try (InputStream input = TemplateVersionPolicyTest.class.getResourceAsStream(path)) {
            assertNotNull(input, () -> "Missing template resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
