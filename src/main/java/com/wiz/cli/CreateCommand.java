package com.wiz.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.Callable;

import com.wiz.core.DevelopmentToolchain;
import com.wiz.core.FrontendTemplate;
import com.wiz.core.ProjectTemplateService;

import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "create",
        mixinStandardHelpOptions = true,
        description = "Create a standalone Spring project from a frontend template.")
public class CreateCommand implements Callable<Integer> {

    private final ToolchainCheck toolchainCheck;

    public CreateCommand() {
        this(new DevelopmentToolchain()::verify);
    }

    CreateCommand(ToolchainCheck toolchainCheck) {
        this.toolchainCheck = Objects.requireNonNull(toolchainCheck, "toolchainCheck");
    }

    @Parameters(index = "0", paramLabel = "PATH", description = "New project path or name.")
    private Path path;

    @Option(names = "--package", required = true,
            description = "Base Java package, for example com.example.demo.")
    private String packageRoot;

    @Option(
            names = "--template",
            defaultValue = "angular-wiz",
            paramLabel = "TEMPLATE",
            converter = FrontendTemplateConverter.class,
            completionCandidates = FrontendTemplateCandidates.class,
            description = "Frontend template: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}.")
    private FrontendTemplate template;

    @Option(names = "--uri", description = "Git repository URI to import. Its backend must already use standard Spring source paths. Requires an explicit --template.")
    private String uri;

    @Option(names = "--path", description = "Local project directory to import. Its backend must already use standard Spring source paths. Requires an explicit --template.")
    private Path sourcePath;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        boolean imported = sourcePath != null || uri != null;
        if (imported && !spec.commandLine().getParseResult().hasMatchedOption("--template")) {
            throw new ParameterException(
                    spec.commandLine(),
                    "--template must be specified explicitly when importing with --uri or --path");
        }
        DevelopmentToolchain.Report toolchain = toolchainCheck.verify();
        ProjectTemplateService.GeneratedProject project = new ProjectTemplateService()
                .create(path, packageRoot, template, uri, sourcePath);

        PrintWriter output = spec.commandLine().getOut();
        output.println("Project created: " + project.root());
        output.println("Java package: " + project.packageRoot());
        output.println("Frontend template: " + project.template().id());
        output.println("Toolchain: " + toolchain.summary());
        printNextCommands(output, project);
        output.flush();
        return 0;
    }

    private void printNextCommands(PrintWriter output, ProjectTemplateService.GeneratedProject project) {
        output.println("Next:");
        output.println("  cd " + project.root());
        switch (project.template()) {
            case ANGULAR_WIZ -> {
                output.println(project.imported() ? "  npm install" : "  npm ci");
                output.println("  npm run wizbuild");
                output.println("  npm run dev");
                output.println("  npm run bundle");
            }
            case ANGULAR, REACT, HTML, JSP -> {
                output.println(project.imported() ? "  npm install" : "  npm ci");
                output.println("  npm run build");
                output.println("  npm run dev");
                output.println("  npm run bundle");
            }
        }
    }

    FrontendTemplate selectedTemplate() {
        return template;
    }

    public static final class FrontendTemplateConverter implements ITypeConverter<FrontendTemplate> {
        @Override
        public FrontendTemplate convert(String value) {
            return FrontendTemplate.fromId(value);
        }
    }

    public static final class FrontendTemplateCandidates implements Iterable<String> {
        @Override
        public Iterator<String> iterator() {
            return FrontendTemplate.ids().iterator();
        }
    }

    @FunctionalInterface
    interface ToolchainCheck {
        DevelopmentToolchain.Report verify();
    }
}
