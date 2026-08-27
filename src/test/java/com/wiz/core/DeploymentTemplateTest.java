package com.wiz.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class DeploymentTemplateTest {

    private static final String COMMON_NGINX =
            "/wiz/templates/project-common/deploy/nginx/default.conf.template";
    private static final String COMMON_APACHE =
            "/wiz/templates/project-common/deploy/apache2/000-default.conf.template";
    private static final String JSP_NGINX =
            "/wiz/templates/project-jsp/deploy/nginx/default.conf.template";
    private static final String JSP_APACHE =
            "/wiz/templates/project-jsp/deploy/apache2/000-default.conf.template";

    @Test
    void backendContainerPreservesArchiveExtensionAndRunsAsNonRoot() throws Exception {
        try (InputStream input = DeploymentTemplateTest.class.getResourceAsStream(
                "/wiz/templates/project-common/deploy/docker/backend.Dockerfile")) {
            assertNotNull(input);
            String dockerfile = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(dockerfile.contains("ENV APP_ARTIFACT=${APP_ARTIFACT}"));
            assertTrue(dockerfile.contains("app/${APP_ARTIFACT} /app/${APP_ARTIFACT}"));
            assertTrue(dockerfile.contains("USER 10001:10001"));
            assertTrue(dockerfile.contains("mkdir -p /app/data"));
            assertTrue(dockerfile.contains("chown -R 10001:10001 /app"));
            assertTrue(dockerfile.contains("exec java -jar \\\"/app/${APP_ARTIFACT}\\\""));
            assertFalse(dockerfile.contains("/app/application\n"),
                    "JSP executable WARs must not lose their .war extension");
        }
    }

    @Test
    void composePersistsTheSampleDatabaseOutsideTheBackendContainer() throws Exception {
        String compose = resource("/wiz/templates/project-common/docker-compose.yaml");

        assertTrue(compose.contains("- backend-data:/app/data"));
        assertTrue(compose.contains("volumes:\n  backend-data:"));
    }

    @Test
    void reverseProxiesDoNotBufferServerSentEvents() throws Exception {
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
    void dynamicApiPrefixUsesLiteralBoundaryMappingsInsteadOfRegexInterpolation() throws Exception {
        String nginx = resource(COMMON_NGINX);
        assertTrue(nginx.contains("location = \"${API_PREFIX}\""));
        assertTrue(nginx.contains("location ^~ \"${API_PREFIX}/\""));
        assertTrue(nginx.contains("v3/api-docs(?:\\.yaml)?"));
        assertTrue(nginx.contains("swagger-ui(?:\\.html)?"));
        assertFalse(nginx.contains("location ~ ^${API_PREFIX}"));

        String apache = resource(COMMON_APACHE);
        assertTrue(apache.contains(
                "ProxyPass \"${API_PREFIX}/\" \"http://${BACKEND_HOST}:${BACKEND_PORT}${API_PREFIX}/\""));
        assertTrue(apache.contains("%{REQUEST_URI} == '${API_PREFIX}'"));
        assertFalse(apache.contains("ProxyPassMatch"));
        assertFalse(apache.contains("^${API_PREFIX}"));
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
        String bundle = resource("/wiz/templates/project-common/deploy/application-bundle.yml");

        assertTrue(application.contains("- file:./public/"));
        assertTrue(application.contains("- file:./target/generated-resources/frontend/"));
        assertTrue(bundle.contains("- file:./public/"));
        assertFalse(application.contains("optional:file:"));
        assertFalse(bundle.contains("optional:file:"));
    }

    @Test
    void proxyImagesNormalizeStaticFilePermissionsForUnprivilegedWorkers() throws Exception {
        String nginxDockerfile = resource("/wiz/templates/project-common/deploy/nginx/Dockerfile");
        String apacheDockerfile = resource("/wiz/templates/project-common/deploy/apache2/Dockerfile");

        assertTrue(nginxDockerfile.contains("chmod -R a=rX /usr/share/nginx/html"));
        assertTrue(apacheDockerfile.contains("chmod -R a=rX /usr/local/apache2/htdocs"));
    }

    private static String resource(String path) throws Exception {
        try (InputStream input = DeploymentTemplateTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
