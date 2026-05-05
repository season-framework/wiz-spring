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
		SpringApplication application = new SpringApplication(WizSpringApplication.class);
		application.run(
				"--wiz.root=" + root,
				"--server.address=" + host,
				"--server.port=" + port);
	}

}
