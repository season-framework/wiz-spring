package com.wiz.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "kill", mixinStandardHelpOptions = true, description = "Kill running WIZ Spring server processes.")
public class KillCommand implements Callable<Integer> {

    @Option(names = "--dry-run", description = "Print matching processes without killing them.")
    private boolean dryRun;

    @Override
    public Integer call() {
        long currentPid = ProcessHandle.current().pid();
        int count = 0;
        for (ProcessHandle process : ProcessHandle.allProcesses().toList()) {
            if (process.pid() == currentPid || !matches(process)) {
                continue;
            }
            count++;
            String command = process.info().commandLine().orElse(process.info().command().orElse(""));
            if (dryRun) {
                System.out.println(process.pid() + " " + command);
            } else {
                process.destroy();
                System.out.println("Killed WIZ process: " + process.pid());
            }
        }
        if (count == 0) {
            System.out.println("No WIZ Spring run processes found.");
        }
        return 0;
    }

    private boolean matches(ProcessHandle process) {
        String command = process.info().commandLine().orElse("");
        String normalized = " " + command + " ";
        boolean wizProcess = normalized.contains("wiz-spring");
        return wizProcess && normalized.contains(" run ");
    }
}
