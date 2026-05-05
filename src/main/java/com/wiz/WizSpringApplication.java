package com.wiz;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.wiz.cli.WizCommand;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.io.FileSystemResource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;

import picocli.CommandLine;

@SpringBootApplication
public class WizSpringApplication {

	public static void main(String[] args) {
		int exitCode = new CommandLine(new WizCommand()).execute(args);
		if (exitCode != 0) {
			System.exit(exitCode);
		}
	}

	public static void runServer(String root, String host, int port) {
		runServer(root, host, port, null, false, null);
	}

	public static void runServer(String root, String host, int port, boolean bundle, String log) {
		runServer(root, host, port, null, bundle, log);
	}

	public static void runServer(String root, String host, Integer port, String project, boolean bundle, String log) {
		SpringApplication application = new SpringApplication(WizSpringApplication.class);
		ArrayList<String> args = new ArrayList<>(serverArgs(root, host, port, project, bundle, log));
		application.run(args.toArray(String[]::new));
	}

	public static List<String> serverArgs(String root, String host, Integer port, String project, boolean bundle, String log) {
		Path workspace = Path.of(root == null || root.isBlank() ? "." : root).toAbsolutePath().normalize();
		String projectName = project == null || project.isBlank() ? defaultProjectName(workspace) : project;
		ArrayList<String> args = new ArrayList<>();
		args.add("--wiz.root=" + workspace);
		args.add("--wiz.project.default-name=" + projectName);
		args.add("--spring.config.additional-location=" + additionalConfigLocations(workspace, projectName));
		if (host != null && !host.isBlank()) {
			args.add("--server.address=" + host);
		}
		if (port != null) {
			args.add("--server.port=" + port);
		}
		args.add("--wiz.bundle=" + bundle);
		if (log != null && !log.isBlank()) {
			args.add("--logging.file.name=" + log);
		}
		return List.copyOf(args);
	}

	private static String defaultProjectName(Path workspace) {
		for (Path config : List.of(workspace.resolve("config/application.yml"), workspace.resolve("config/application.yaml"), workspace.resolve("config/wiz.yml"), workspace.resolve("config/wiz.yaml"))) {
			Properties properties = yaml(config);
			String value = first(properties, "wiz.project.default-name", "wiz.default-project");
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return "main";
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
			String value = properties.getProperty(key);
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	private static String additionalConfigLocations(Path workspace, String projectName) {
		return "optional:" + directoryUri(workspace.resolve("config"))
				+ ",optional:" + directoryUri(workspace.resolve("project").resolve(projectName).resolve("config"));
	}

	private static String directoryUri(Path directory) {
		String uri = directory.toUri().toString();
		return uri.endsWith("/") ? uri : uri + "/";
	}

}
