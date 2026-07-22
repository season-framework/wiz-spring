package com.wiz;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.wiz.cli.WizCommand;
import com.wiz.core.PortFinder;
import com.wiz.logging.ProcessLogManager;
import com.wiz.runtime.EmbeddedWorkspace;
import com.wiz.runtime.PathService;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.core.io.FileSystemResource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;

import picocli.CommandLine;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WizSpringApplication {

	public static final String DEFAULT_RUN_PROFILE = "dev";
	public static final String DEFAULT_EMBEDDED_PROFILE = "prod";
	public static final String DEFAULT_RUN_HOST = "0.0.0.0";
	public static final int DEFAULT_RUN_PORT = 3000;

	public static void main(String[] args) {
		if (args.length == 0) {
			try {
				var embedded = EmbeddedWorkspace.extractIfPresent();
				if (embedded.isPresent()) {
					EmbeddedWorkspace.Launch launch = embedded.get();
					runServer(launch.root().toString(), null, null, true, null, DEFAULT_EMBEDDED_PROFILE, false);
					return;
				}
			} catch (Exception exception) {
				System.err.println("Failed to start embedded WIZ workspace: " + exception.getMessage());
				System.exit(1);
			}
		}
		int exitCode = new CommandLine(new WizCommand()).execute(args);
		if (exitCode != 0) {
			System.exit(exitCode);
		}
	}

	public static void runServer(String root, String host, int port) {
		runServer(root, host, port, false, null);
	}

	public static void runServer(String root, String host, int port, boolean bundle, String log) {
		runServer(root, host, Integer.valueOf(port), bundle, log);
	}

	public static void runServer(String root, String host, Integer port, boolean bundle, String log) {
		runServer(root, host, port, bundle, log, DEFAULT_RUN_PROFILE, false);
	}

	public static void runServer(String root, String host, Integer port, boolean bundle, String log, String profile, boolean profileOverride) {
		runServer(resolveRunSettings(root, host, port, bundle, log, profile, profileOverride));
	}

	public static void runServer(RunSettings settings) {
		if (settings == null) {
			throw new IllegalArgumentException("WIZ run settings are required");
		}
		SpringApplication application = new SpringApplication(WizSpringApplication.class);
		ProcessLogManager.install(settings.log());
		ProcessLogManager.claimSpringFileTarget(application, settings.log());
		application.run(settings.args().toArray(String[]::new));
	}

	public static List<String> serverArgs(String root, String host, Integer port, boolean bundle, String log) {
		return serverArgs(root, host, port, bundle, log, DEFAULT_RUN_PROFILE, false);
	}

	public static List<String> serverArgs(String root, String host, Integer port, boolean bundle, String log, String profile) {
		return serverArgs(root, host, port, bundle, log, profile, true);
	}

	public static List<String> serverArgs(String root, String host, Integer port, boolean bundle, String log, String profile, boolean profileOverride) {
		return resolveRunSettings(root, host, port, bundle, log, profile, profileOverride).args();
	}

	public static RunSettings resolveRunSettings(String root, String host, Integer port, boolean bundle, String log, String profile, boolean profileOverride) {
		Path workspace = Path.of(root == null || root.isBlank() ? "." : root).toAbsolutePath().normalize();
		String normalizedProfile = normalizeProfile(profile);
		Properties properties = runProperties(workspace, normalizedProfile);
		String resolvedHost = host == null || host.isBlank() ? stringProperty(properties, "server.address", DEFAULT_RUN_HOST) : host.trim();
		int requestedPort = port == null ? intProperty(properties, "server.port", DEFAULT_RUN_PORT) : port;
		PortFinder.validatePort(requestedPort);
		int resolvedPort = PortFinder.nextAvailablePort(requestedPort, resolvedHost);
		String locations = additionalConfigLocations(workspace);
		return new RunSettings(workspace, resolvedHost, requestedPort, resolvedPort, bundle, normalizeLog(log), normalizedProfile, profileOverride, locations);
	}

	public record RunSettings(
			Path workspace,
			String host,
			int requestedPort,
			int port,
			boolean bundle,
			String log,
			String profile,
			boolean profileOverride,
			String additionalConfigLocations) {
		public boolean portChanged() {
			return requestedPort != port;
		}

		public List<String> args() {
			ArrayList<String> args = new ArrayList<>();
			args.add("--wiz.root=" + workspace);
			args.add("--spring.config.additional-location=" + additionalConfigLocations);
			args.add((profileOverride ? "--spring.profiles.active=" : "--spring.profiles.default=") + profile);
			args.add("--server.address=" + host);
			args.add("--server.port=" + port);
			args.add("--wiz.bundle=" + bundle);
			if (log != null && !log.isBlank()) {
				args.add("--spring.output.ansi.enabled=never");
			}
			return List.copyOf(args);
		}
	}

	private static String normalizeLog(String log) {
		if (log == null || log.isBlank()) {
			return null;
		}
		Path path = Path.of(log.trim()).toAbsolutePath().normalize();
		if (Files.isDirectory(path)) {
			throw new IllegalArgumentException("WIZ log path must be a file: " + path);
		}
		return path.toString();
	}

	private static String normalizeProfile(String profile) {
		String value = profile == null || profile.isBlank() ? DEFAULT_RUN_PROFILE : profile.trim();
		if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
			throw new IllegalArgumentException("Spring profile must be a single safe profile name");
		}
		return value;
	}

	private static Properties yaml(Path path) {
		if (!Files.isRegularFile(path)) {
			return new Properties();
		}
		YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
		factory.setResources(new FileSystemResource(path));
		Properties properties = factory.getObject();
		return properties == null ? new Properties() : properties;
	}

	private static String first(Properties properties, String... keys) {
		for (String key : keys) {
			String value = propertyValue(properties, key);
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	private static Properties runProperties(Path workspace, String profile) {
		Properties properties = new Properties();
		properties.setProperty("server.address", DEFAULT_RUN_HOST);
		properties.setProperty("server.port", String.valueOf(DEFAULT_RUN_PORT));
		for (Path config : configFiles(workspace.resolve("config"), profile)) {
			properties.putAll(yaml(config));
		}
		return properties;
	}

	private static List<Path> configFiles(Path directory, String profile) {
		ArrayList<Path> paths = new ArrayList<>();
		paths.add(directory.resolve("application.yml"));
		paths.add(directory.resolve("application.yaml"));
		if (profile != null && !profile.isBlank()) {
			paths.add(directory.resolve("application-" + profile + ".yml"));
			paths.add(directory.resolve("application-" + profile + ".yaml"));
		}
		return paths;
	}

	private static String stringProperty(Properties properties, String key, String fallback) {
		String value = propertyValue(properties, key);
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private static int intProperty(Properties properties, String key, int fallback) {
		String value = propertyValue(properties, key);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException(key + " must be a concrete integer for wiz-spring run: " + value, exception);
		}
	}

	private static String propertyValue(Properties properties, String key) {
		Object value = properties.get(key);
		return value == null ? null : String.valueOf(value);
	}

	private static String additionalConfigLocations(Path workspace) {
		return "optional:" + directoryUri(workspace.resolve("config"));
	}

	private static String directoryUri(Path directory) {
		String uri = directory.toUri().toString();
		return uri.endsWith("/") ? uri : uri + "/";
	}

}
