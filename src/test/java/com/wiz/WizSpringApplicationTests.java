package com.wiz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.wiz.config.WizProjectProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

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
		Files.writeString(workspace.resolve("config/application.yml"), "server:\n  port: 19091\nwiz:\n  project:\n    default-name: dev\n");

		List<String> args = WizSpringApplication.serverArgs(workspace.toString(), null, null, null, false, null);

		assertTrue(args.contains("--wiz.project.default-name=dev"));
		assertTrue(args.contains("--spring.profiles.default=dev"));
		assertTrue(args.contains("--server.address=0.0.0.0"));
		assertTrue(args.contains("--server.port=19091"));
		assertTrue(args.stream().anyMatch(arg -> arg.startsWith("--spring.config.additional-location=")
				&& arg.contains(workspace.resolve("config").toUri().toString())
				&& arg.contains(workspace.resolve("project/dev/config").toUri().toString())));
	}

	@Test
	void runSettingsScanFromConfiguredBusyPort() throws Exception {
		Path workspace = tempDir.resolve("scan-workspace");
		Files.createDirectories(workspace.resolve("config"));
		Files.createDirectories(workspace.resolve("project/main/config"));
		try (ServerSocket busy = new ServerSocket(0)) {
			int busyPort = busy.getLocalPort();
			Files.writeString(workspace.resolve("config/application.yml"), "server:\n  port: " + busyPort + "\nwiz:\n  project:\n    default-name: main\n");

			WizSpringApplication.RunSettings settings = WizSpringApplication.resolveRunSettings(workspace.toString(), "127.0.0.1", null, null, false, null, null, false);

			assertEquals(busyPort, settings.requestedPort());
			assertTrue(settings.port() > busyPort);
			assertTrue(settings.portChanged());
			assertTrue(settings.args().contains("--server.port=" + settings.port()));
		}
	}

	@Test
	void serverArgsCanActivateExplicitProfile() throws Exception {
		Path workspace = tempDir.resolve("profile-workspace");
		Files.createDirectories(workspace.resolve("config"));
		Files.createDirectories(workspace.resolve("project/main/config"));

		List<String> args = WizSpringApplication.serverArgs(workspace.toString(), null, null, null, false, null, "prod");

		assertTrue(args.contains("--spring.profiles.active=prod"));
		assertTrue(args.stream().noneMatch(arg -> arg.startsWith("--spring.profiles.default=")));
	}

	@Test
	void embeddedServerArgsUseProdDefaultProfile() throws Exception {
		Path workspace = tempDir.resolve("embedded-workspace");
		Files.createDirectories(workspace.resolve("config"));
		Files.createDirectories(workspace.resolve("project/main/config"));

		List<String> args = WizSpringApplication.serverArgs(workspace.toString(), null, null, "main", true, null, WizSpringApplication.DEFAULT_EMBEDDED_PROFILE, false);

		assertTrue(args.contains("--spring.profiles.default=prod"));
		assertTrue(args.contains("--wiz.bundle=true"));
	}

	@Test
	void profileSpecificApplicationFilesOverrideProjectCookieSelectionDefault() {
		try (ConfigurableApplicationContext dev = applicationContext("dev")) {
			assertTrue(dev.getBean(WizProjectProperties.class).isCookieSelectionEnabled());
		}
		try (ConfigurableApplicationContext prod = applicationContext("prod")) {
			assertFalse(prod.getBean(WizProjectProperties.class).isCookieSelectionEnabled());
		}
	}

	private ConfigurableApplicationContext applicationContext(String profile) {
		SpringApplication application = new SpringApplication(WizSpringApplication.class);
		application.setWebApplicationType(WebApplicationType.NONE);
		return application.run(
				"--spring.profiles.active=" + profile,
				"--wiz.root=" + tempDir.resolve("profile-" + profile));
	}

}
