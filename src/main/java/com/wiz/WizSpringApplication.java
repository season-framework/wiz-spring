package com.wiz;

import com.wiz.cli.WizCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
		runServer(root, host, port, false, null);
	}

	public static void runServer(String root, String host, int port, boolean bundle, String log) {
		SpringApplication application = new SpringApplication(WizSpringApplication.class);
		java.util.ArrayList<String> args = new java.util.ArrayList<>();
		args.add("--wiz.root=" + root);
		args.add("--server.address=" + host);
		args.add("--server.port=" + port);
		args.add("--wiz.bundle=" + bundle);
		if (log != null && !log.isBlank()) {
			args.add("--logging.file.name=" + log);
		}
		application.run(args.toArray(String[]::new));
	}

}
