package com.wiz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.wiz.config.WizRuntimeProperties;
import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.runtime.PathService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.servlet.Session.SessionTrackingMode;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootTest
class WizSpringApplicationTests {

	@TempDir
	Path tempDir;

	@Test
	void contextLoads() {
	}

	@Test
	void serverArgsIncludeWorkspaceApplicationConfigLocation() throws Exception {
		Path workspace = tempDir.resolve("workspace");
		Files.createDirectories(workspace.resolve("config"));
		Files.writeString(workspace.resolve("config/application.yml"), "server:\n  port: 19091\nwiz:\n  java:\n    package-root: com.example.app\n");

		List<String> args = WizSpringApplication.serverArgs(workspace.toString(), null, null, false, null);

		assertTrue(args.contains("--spring.profiles.default=dev"));
		assertTrue(args.contains("--server.address=0.0.0.0"));
		assertTrue(args.contains("--server.port=19091"));
		assertTrue(args.stream().anyMatch(arg -> arg.startsWith("--spring.config.additional-location=")
				&& arg.contains(workspace.resolve("config").toUri().toString())));
	}

	@Test
	void runSettingsScanFromConfiguredBusyPort() throws Exception {
		Path workspace = tempDir.resolve("scan-workspace");
		Files.createDirectories(workspace.resolve("config"));
		Files.createDirectories(workspace.resolve("config"));
		try (ServerSocket busy = new ServerSocket(0)) {
			int busyPort = busy.getLocalPort();
			Files.writeString(workspace.resolve("config/application.yml"), "server:\n  port: " + busyPort + "\nwiz:\n  java:\n    package-root: com.example.app\n");

			WizSpringApplication.RunSettings settings = WizSpringApplication.resolveRunSettings(workspace.toString(), "127.0.0.1", null, false, null, null, false);

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
		Files.createDirectories(workspace.resolve("config"));

		List<String> args = WizSpringApplication.serverArgs(workspace.toString(), null, null, false, null, "prod");

		assertTrue(args.contains("--spring.profiles.active=prod"));
		assertTrue(args.stream().noneMatch(arg -> arg.startsWith("--spring.profiles.default=")));
	}

	@Test
	void runSettingsReadTheSelectedWorkspaceProfileAfterCommonConfig() throws Exception {
		Path workspace = tempDir.resolve("selected-profile-workspace");
		Files.createDirectories(workspace.resolve("config"));
		Files.writeString(workspace.resolve("config/application.yml"), "server:\n  port: 19081\n");
		Files.writeString(workspace.resolve("config/application-dev.yml"), "server:\n  port: 19082\n");
		Files.writeString(workspace.resolve("config/application-prod.yml"), "server:\n  port: 19083\n");

		WizSpringApplication.RunSettings dev = WizSpringApplication.resolveRunSettings(
				workspace.toString(), null, null, false, null, "dev", true);
		WizSpringApplication.RunSettings prod = WizSpringApplication.resolveRunSettings(
				workspace.toString(), null, null, false, null, "prod", true);

		assertEquals(19082, dev.requestedPort());
		assertEquals(19083, prod.requestedPort());
	}

	@Test
	void embeddedServerArgsUseProdDefaultProfile() throws Exception {
		Path workspace = tempDir.resolve("embedded-workspace");
		Files.createDirectories(workspace.resolve("config"));
		Files.createDirectories(workspace.resolve("config"));

		List<String> args = WizSpringApplication.serverArgs(workspace.toString(), null, null, true, null, WizSpringApplication.DEFAULT_EMBEDDED_PROFILE, false);

		assertTrue(args.contains("--spring.profiles.default=prod"));
		assertTrue(args.contains("--wiz.bundle=true"));
	}

	@Test
	void runtimeDefaultsAndWorkspaceSessionCookieProfilesApply() throws Exception {
		try (ConfigurableApplicationContext dev = applicationContext("dev")) {
			assertTrue(dev.getBean(WizRuntimeProperties.class).isWarmupEnabled());
			assertSessionCookiePolicy(dev, false);
		}
		try (ConfigurableApplicationContext prod = applicationContext("prod")) {
			assertTrue(prod.getBean(WizRuntimeProperties.class).isWarmupEnabled());
			assertSessionCookiePolicy(prod, true);
		}
	}

	private ConfigurableApplicationContext applicationContext(String profile) throws Exception {
		Path workspace = tempDir.resolve("profile-" + profile);
		new WorkspaceService().createWorkspace(workspace);
		new ProjectService(new PathService(workspace)).createApp(null, null);
		SpringApplication application = new SpringApplication(WizSpringApplication.class);
		application.setWebApplicationType(WebApplicationType.NONE);
		return application.run(
				"--spring.profiles.active=" + profile,
				"--spring.config.additional-location=optional:" + workspace.resolve("config").toUri(),
				"--wiz.root=" + workspace);
	}

	private void assertSessionCookiePolicy(ConfigurableApplicationContext context, boolean secure) {
		ServerProperties properties = Binder.get(context.getEnvironment())
				.bind("server", ServerProperties.class)
				.orElseThrow(() -> new AssertionError("server properties were not bound"));
		Cookie cookie = properties.getServlet().getSession().getCookie();
		assertEquals(Set.of(SessionTrackingMode.COOKIE), properties.getServlet().getSession().getTrackingModes());
		assertEquals(Boolean.TRUE, cookie.getHttpOnly());
		assertEquals(Cookie.SameSite.LAX, cookie.getSameSite());
		assertEquals(secure, Boolean.TRUE.equals(cookie.getSecure()));
	}

}
