package com.wiz.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.runtime.PathService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class WizMcpToolServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesPortedToolSet() {
        WizMcpToolService service = new WizMcpToolService(tempDir, null);

        assertEquals(55, service.toolDefinitions().size());
        assertTrue(service.toolDefinitions().stream().anyMatch(tool -> tool.get("name").equals("wiz_app_build")));
        assertTrue(service.toolDefinitions().stream().anyMatch(tool -> tool.get("name").equals("wiz_package_create_route")));
        assertTrue(service.toolDefinitions().stream().anyMatch(tool -> tool.get("name").equals("wiz_app_jar")));
        assertTrue(service.toolDefinitions().stream().noneMatch(tool -> tool.get("name").equals("wiz_app_pip_install")));
        assertTrue(service.toolDefinitions().stream().noneMatch(tool -> tool.get("name").toString().startsWith("wiz_" + "project_")));
    }

    @Test
    void handlesWorkspaceProjectAndSourceTools() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        new ProjectService(new PathService(workspace)).createApp(null, null);
        WizMcpToolService service = new WizMcpToolService(workspace, null);

        Map<String, Object> status = toolData(service.callTool("wiz_workspace_status", Map.of()));
        assertEquals("com.wiz.app", status.get("javaPackageRoot"));
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) status.get("workspaceMetadata");
        assertEquals("java", metadata.get("workspace"));
        assertEquals("wiz-spring", metadata.get("runtimeName"));

        Map<String, Object> app = toolData(service.callTool("wiz_source_create_app", Map.of(
                "appType", "page",
                "namespace", "mcp")));
        assertTrue(Boolean.TRUE.equals(app.get("success")));
        assertTrue(Files.exists(workspace.resolve("src/app/page.mcp/api.java")));

        Map<String, Object> file = toolData(service.callTool("wiz_source_write_file", Map.of(
                "appPath", "page.mcp",
                "fileName", "notes.txt",
                "content", "hello")));
        assertTrue(Boolean.TRUE.equals(file.get("success")));

        Map<String, Object> read = toolData(service.callTool("wiz_source_read_file", Map.of(
                "appPath", "page.mcp",
                "fileName", "notes.txt")));
        assertEquals("hello", read.get("content"));

        Map<String, Object> controller = toolData(service.callTool("wiz_source_create_controller", Map.of("controller", "mcp")));
        assertTrue(Boolean.TRUE.equals(controller.get("success")));
        assertTrue(Files.exists(workspace.resolve("src/controller/McpController.java")));

        Map<String, Object> dependencyInfo = toolData(service.callTool("wiz_app_dependency_info", Map.of()));
        assertEquals("main", dependencyInfo.get("app"));

        Files.createDirectories(workspace.resolve("bundle/www"));
        Files.writeString(workspace.resolve("bundle/www/index.html"), "<html></html>\n");
        Path runtimeJar = tempDir.resolve("runtime.jar");
        writeFakeRuntimeJar(runtimeJar);
        Path outputJar = tempDir.resolve("main.jar");
        Map<String, Object> jar = toolData(service.callTool("wiz_app_jar", Map.of(
                "skipBuild", true,
                "runtimeJar", runtimeJar.toString(),
                "output", outputJar.toString())));
        assertTrue(Boolean.TRUE.equals(jar.get("success")));
        assertTrue(Files.exists(outputJar));

        Map<String, Object> packages = toolData(service.callTool("wiz_package_create", Map.of("namespace", "blog")));
        assertTrue(Boolean.TRUE.equals(packages.get("success")));
        Map<String, Object> packageController = toolData(service.callTool("wiz_package_create_controller", Map.of(
                "packageName", "blog",
                "controller", "editor")));
        assertTrue(Boolean.TRUE.equals(packageController.get("success")));
        assertTrue(Files.exists(workspace.resolve("src/portal/blog/controller/EditorController.java")));
        Map<String, Object> portalRoute = toolData(service.callTool("wiz_package_create_route", Map.of(
                "packageName", "blog",
                "id", "feed",
                "routePath", "/feed")));
        assertTrue(Boolean.TRUE.equals(portalRoute.get("success")));
        assertTrue(Files.exists(workspace.resolve("src/portal/blog/route/feed/route.java")));

        Map<String, Object> search = toolData(service.callTool("wiz_app_search_apps", Map.of("query", "mcp")));
        assertFalse(((java.util.List<?>) search.get("results")).isEmpty());

        Map<String, Object> deletedPackage = toolData(service.callTool("wiz_package_delete", Map.of("packageName", "blog")));
        assertTrue(Boolean.TRUE.equals(deletedPackage.get("success")));
        assertTrue(!Files.exists(workspace.resolve("src/portal/blog")));
    }

    @Test
    void rejectsEscapingProjectPaths() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        new WorkspaceService().createWorkspace(workspace);
        new ProjectService(new PathService(workspace)).createApp(null, null);
        WizMcpToolService service = new WizMcpToolService(workspace, null);

        assertThrows(IllegalArgumentException.class,
                () -> service.callTool("wiz_app_read_file", Map.of("relativePath", "../config/application.yml")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolData(Map<String, Object> result) throws Exception {
        java.util.List<Map<String, Object>> content = (java.util.List<Map<String, Object>>) result.get("content");
        String text = content.get(0).get("text").toString();
        return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {
        });
    }

    private void writeFakeRuntimeJar(Path jar) throws Exception {
        try (java.util.jar.JarOutputStream output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new java.util.jar.JarEntry("META-INF/MANIFEST.MF"));
            output.write("Manifest-Version: 1.0\nMain-Class: com.wiz.WizSpringApplication\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }
}
