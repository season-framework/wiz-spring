package com.wiz;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class WizSpringApplicationTests {

	@TempDir
	Path tempDir;

	@Test
	void contextLoads() {
	}

	@Test
	void serverArgsIncludeWorkspaceAndProjectApplicationConfigLocations() throws Exception {
		Path workspace = tempDir.resolve("workspace");
		Files.createDirectories(workspace.resolve("config"));
		Files.createDirectories(workspace.resolve("project/dev/config"));
		Files.writeString(workspace.resolve("config/application.yml"), "wiz:\n  project:\n    default-name: dev\n");

		List<String> args = WizSpringApplication.serverArgs(workspace.toString(), null, null, null, false, null);

		assertTrue(args.contains("--wiz.project.default-name=dev"));
		assertTrue(args.stream().anyMatch(arg -> arg.startsWith("--spring.config.additional-location=")
				&& arg.contains(workspace.resolve("config").toUri().toString())
				&& arg.contains(workspace.resolve("project/dev/config").toUri().toString())));
		assertTrue(args.stream().noneMatch(arg -> arg.startsWith("--server.port=")));
	}

}
