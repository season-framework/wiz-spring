package com.wiz.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.wiz.runtime.ProjectContext;

final class PugBuildService {

    private static final Duration PUG_TIMEOUT = Duration.ofMinutes(2);
    private static final int OUTPUT_CAP_BYTES = 32 * 1024;

    private final CommandExecutor commandExecutor;

    PugBuildService(CommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor;
    }

    CommandResult compile(ProjectContext project, Path angularRoot) throws IOException, InterruptedException {
        return compile(project, angularRoot, BuildLogger.quiet());
    }

    CommandResult compile(ProjectContext project, Path angularRoot, BuildLogger logger) throws IOException, InterruptedException {
        List<Path> pugFiles = pugFiles(angularRoot.resolve("src"));
        if (pugFiles.isEmpty()) {
            return new CommandResult("frontend-pug", List.of("node"), angularRoot, 0, 0, false, false, "No Pug templates to compile");
        }
        Path script = angularRoot.resolve(".wiz/pug-build.mjs");
        Files.createDirectories(script.getParent());
        Files.writeString(script, pugBuildScript());
        ArrayList<String> argv = new ArrayList<>();
        argv.add("node");
        argv.add(script.toString());
        pugFiles.stream().map(Path::toString).forEach(argv::add);
        return commandExecutor.run("frontend-pug", project.root(), angularRoot, argv, PUG_TIMEOUT, OUTPUT_CAP_BYTES, logger);
    }

    private List<Path> pugFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".pug"))
                    .sorted()
                    .toList();
        }
    }

    private String pugBuildScript() {
        return "import fs from 'node:fs';\n"
                + "import path from 'node:path';\n"
                + "import pug from 'pug';\n"
                + "for (const file of process.argv.slice(2)) {\n"
                + "  const html = pug.renderFile(file, { doctype: 'html', pretty: false });\n"
                + "  const output = file.replace(/\\.pug$/, '.html');\n"
                + "  fs.mkdirSync(path.dirname(output), { recursive: true });\n"
                + "  fs.writeFileSync(output, html + '\\n');\n"
                + "}\n";
    }
}
