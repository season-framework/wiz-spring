package com.wiz.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class DeploymentTemplateTest {

    private static final String COMMON_NGINX =
            "/wiz/templates/project-common/deploy/nginx/default.conf.example";
    private static final String COMMON_APACHE =
            "/wiz/templates/project-common/deploy/apache2/wiz.conf.example";
    private static final String JSP_NGINX =
            "/wiz/templates/project-jsp/deploy/nginx/default.conf.example";
    private static final String JSP_APACHE =
            "/wiz/templates/project-jsp/deploy/apache2/wiz.conf.example";

    @Test
    void composeRunsOnlyTheApplicationAndPersistsItsData() throws Exception {
        String compose = resource("/wiz/templates/project-common/docker-compose.yaml");

        assertTrue(compose.contains("  application:"));
        assertTrue(compose.contains("image: eclipse-temurin:25-jre"));
        assertTrue(compose.contains("java\", \"-jar\", \"${APP_ARTIFACT"));
        assertTrue(compose.contains("./public:/opt/application/public:ro"));
        assertTrue(compose.contains("application-data:/opt/application/data"));
        assertTrue(compose.contains("volumes:\n  application-data:"));
        assertFalse(compose.contains("  nginx:"));
        assertFalse(compose.contains("  apache2:"));
        assertFalse(compose.contains("build:"));
        assertFalse(compose.contains("dockerfile:"));
    }

    @Test
    void reverseProxyExamplesDoNotBufferServerSentEvents() throws Exception {
        for (String path : new String[] {COMMON_NGINX, JSP_NGINX}) {
            String nginx = resource(path);
            assertTrue(nginx.contains("proxy_buffering off;"), path);
            assertTrue(nginx.contains("proxy_cache off;"), path);
            assertTrue(nginx.contains("proxy_read_timeout 1h;"), path);
        }

        for (String path : new String[] {COMMON_APACHE, JSP_APACHE}) {
            String apache = resource(path);
            assertTrue(apache.contains("flushpackets=on"), path);
            assertTrue(apache.contains("timeout=3600"), path);
        }
    }

    @Test
    void proxyExamplesUseSafeLiteralApiBoundaryMappings() throws Exception {
        String nginx = resource(COMMON_NGINX);
        assertTrue(nginx.contains("location = /api"));
        assertTrue(nginx.contains("location ^~ /api/"));
        assertTrue(nginx.contains("v3/api-docs(?:\\.yaml)?"));
        assertTrue(nginx.contains("swagger-ui(?:\\.html)?"));
        assertFalse(nginx.contains("${API_PREFIX}"));

        String apache = resource(COMMON_APACHE);
        assertTrue(apache.contains("ProxyPass \"/api/\" \"http://127.0.0.1:8080/api/\""));
        assertTrue(apache.contains("%{REQUEST_URI} == '/api'"));
        assertFalse(apache.contains("ProxyPassMatch"));
        assertFalse(apache.contains("${API_PREFIX}"));
    }

    @Test
    void staticAssetCachesAreImmutableOnlyForHashedFileNames() throws Exception {
        for (String path : new String[] {COMMON_NGINX, JSP_NGINX}) {
            String nginx = resource(path);
            assertTrue(nginx.contains("default \"no-cache, must-revalidate\";"), path);
            assertTrue(nginx.contains("[-.][A-Za-z0-9_-]{8,}"), path);
            assertTrue(nginx.contains("public, max-age=604800, immutable"), path);
            assertFalse(nginx.contains("expires 7d"), path);
            assertFalse(nginx.contains("Cache-Control \"public, immutable\""), path);
        }

        for (String path : new String[] {COMMON_APACHE, JSP_APACHE}) {
            String apache = resource(path);
            assertTrue(apache.contains("Header always set Cache-Control \"no-cache, must-revalidate\""), path);
            assertTrue(apache.contains("[-.][A-Za-z0-9_-]{8,}"), path);
            assertTrue(apache.contains(
                    "Header always set Cache-Control \"public, max-age=604800, immutable\""), path);
        }
    }

    @Test
    void apacheSpaFallbackRejectsMissingFileLikePaths() throws Exception {
        String commonApache = resource(COMMON_APACHE);
        assertTrue(commonApache.contains("RewriteCond %{REQUEST_URI} !/[^/]+\\.[^/]+$ [NC]"));
        assertTrue(commonApache.contains("RewriteRule ^ /index.html [L]"));

        String jspApache = resource(JSP_APACHE);
        assertFalse(jspApache.contains("RewriteRule ^ /index.html"),
                "JSP renders through Spring and must not add a static SPA fallback");
    }

    @Test
    void springStaticLocationsUseResolvableFileResources() throws Exception {
        String application = resource("/wiz/templates/project-common/src/main/resources/application.yml");

        assertTrue(application.contains("- file:./public/"));
        assertTrue(application.contains("- file:./target/generated-resources/frontend/"));
        assertFalse(application.contains("optional:file:"));
    }

    @Test
    void generatedConfigurationAndBundleAreMinimalAndExplicit() throws Exception {
        String environment = resource("/wiz/templates/project-common/.env");
        String projectHelpers = resource("/wiz/templates/project-common/scripts/lib/project.mjs");
        String bundle = resource("/wiz/templates/project-common/scripts/bundle.mjs");
        String manifest = resource("/wiz/templates/project-common.files");

        assertTrue(manifest.lines().anyMatch(".env"::equals));
        assertFalse(manifest.contains(".env.example"));
        assertTrue(environment.contains("SERVER_PORT=8080"));
        assertTrue(environment.contains("SPRING_PROFILES_ACTIVE=dev"));
        assertFalse(environment.contains("BUNDLE_DIR"));
        assertFalse(environment.contains("HTTP_PORT"));
        assertFalse(environment.lines().anyMatch("SPRINGDOC_API_DOCS_ENABLED=false"::equals));
        assertFalse(environment.lines().anyMatch("SPRINGDOC_SWAGGER_UI_ENABLED=false"::equals));
        assertTrue(projectHelpers.contains("process.loadEnvFile(projectEnvironmentFile)"));
        assertTrue(bundle.contains("writeFile(path.join(stage, '.env')"));
        assertTrue(bundle.contains("path.join(stage, artifactName)"));
        assertTrue(bundle.contains("path.join(stage, 'public')"));
        assertTrue(bundle.contains("path.join(stage, 'docker-compose.yaml')"));
        assertTrue(bundle.contains("'SPRING_PROFILES_ACTIVE=prod'"));
        assertFalse(bundle.contains(".env.example"));
        assertFalse(bundle.contains("manifest.json"));
        assertFalse(bundle.contains("SHA256SUMS"));
        assertFalse(bundle.contains("run.sh"));
        assertFalse(bundle.contains("application-bundle.yml"));
        assertFalse(bundle.contains("createHash"));
        assertFalse(manifest.contains("deploy/docker/backend.Dockerfile"));
        assertFalse(manifest.contains("deploy/nginx/Dockerfile"));
        assertFalse(manifest.contains("deploy/apache2/Dockerfile"));
        assertTrue(manifest.contains("deploy/nginx/default.conf.example"));
        assertTrue(manifest.contains("deploy/apache2/wiz.conf.example"));
    }

    private static String resource(String path) throws Exception {
        try (InputStream input = DeploymentTemplateTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
