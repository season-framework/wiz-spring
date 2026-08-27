package com.wiz.cli;

import java.io.PrintWriter;
import java.util.concurrent.Callable;

import com.wiz.core.FrontendTemplate;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
        name = "templates",
        mixinStandardHelpOptions = true,
        description = "List frontend templates available to wiz-spring create.")
public final class TemplatesCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter output = spec.commandLine().getOut();
        output.println("Available project templates:");
        for (FrontendTemplate template : FrontendTemplate.values()) {
            String defaultMarker = template == FrontendTemplate.ANGULAR_WIZ ? " (default)" : "";
            output.printf("  %-12s %s%s%n", template.id(), template.description(), defaultMarker);
        }
        output.flush();
        return 0;
    }
}
