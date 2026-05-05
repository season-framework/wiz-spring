package com.wiz.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectInventoryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void reportsPythonPortingInventoryMetadataAndGeneratesStubs() throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project.resolve("src/app/page.local"));
        Files.createDirectories(project.resolve("src/controller"));
        Files.createDirectories(project.resolve("src/model"));
        Files.createDirectories(project.resolve("src/route/custom.echo"));
        Files.createDirectories(project.resolve("src/portal/post/app/list"));
        Files.createDirectories(project.resolve("src/portal/post"));
        Files.writeString(project.resolve("src/app/page.local/app.json"), "{\"id\":\"page.local\",\"controller\":\"user\",\"viewuri\":\"/local\",\"mode\":\"page\",\"template\":\"wiz-page-local()\"}\n");
        Files.writeString(project.resolve("src/app/page.local/api.py"), "def status():\n    pass\n");
        Files.writeString(project.resolve("src/app/page.local/socket.py"), "class Controller:\n    pass\n");
        Files.writeString(project.resolve("src/controller/base.py"), "def before():\n    pass\n");
        Files.writeString(project.resolve("src/model/sample.py"), "class Sample:\n    pass\n");
        Files.writeString(project.resolve("src/route/custom.echo/app.json"), "{\"id\":\"custom.echo\",\"route\":\"/echo/<name>\",\"methods\":[\"GET\"]}\n");
        Files.writeString(project.resolve("src/portal/post/portal.json"), "{\"id\":\"post\",\"use_app\":true,\"use_route\":false,\"use_model\":true}\n");
        Files.writeString(project.resolve("src/portal/post/app/list/app.json"), "{\"controller\":\"base\",\"viewuri\":\"/posts\"}\n");
        Files.writeString(project.resolve("src/portal/post/app/list/api.py"), "def search():\n    pass\n");

        boolean wroteReport = new ProjectInventoryService().writeReportIfPythonProject(project, true);

        assertTrue(wroteReport);
        assertTrue(Files.exists(project.resolve("src/app/page.local/api.java.stub")));
        assertTrue(Files.exists(project.resolve("src/portal/post/app/list/api.java.stub")));
        String stub = Files.readString(project.resolve("src/app/page.local/api.java.stub"));
        assertTrue(stub.contains("WizContext"));
        assertTrue(stub.contains("WizResult"));

        String json = Files.readString(project.resolve("migration-report.json"));
        assertTrue(json.contains("controllerPythonFiles"));
        assertTrue(json.contains("src/controller/base.py"));
        assertTrue(json.contains("componentMetadata"));
        assertTrue(json.contains("page.local"));
        assertTrue(json.contains("portalMetadata"));
        assertTrue(json.contains("use_route"));
        assertTrue(json.contains("generatedStubFiles"));

        String markdown = Files.readString(project.resolve("migration-report.md"));
        assertTrue(markdown.contains("Porting Order"));
        assertTrue(markdown.contains("defaultJavaClass"));
        assertTrue(markdown.contains("use_route=false"));
    }

    @Test
    void emptyProjectHasNoPythonSource() throws Exception {
        Path project = tempDir.resolve("empty");
        Files.createDirectories(project.resolve("src/app/page.local"));
        Files.writeString(project.resolve("src/app/page.local/app.json"), "{}\n");

        ProjectInventoryService.ProjectInventory inventory = new ProjectInventoryService().inventory(project);

        assertEquals(false, inventory.hasPythonSource());
    }
}