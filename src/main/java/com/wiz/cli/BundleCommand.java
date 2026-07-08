package com.wiz.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import com.wiz.runtime.PathService;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "bundle", mixinStandardHelpOptions = true, description = "Create a deployable WIZ runtime bundle.")
public class BundleCommand implements Callable<Integer> {

    @Option(names = "--root", description = "WIZ workspace root. Defaults to auto-detecting from the current directory.")
    private Path root;

    @Option(names = "--output", description = "Output bundle directory. Defaults to <workspace>/target/runtime-bundle.")
    private Path output;

    @Override
    public Integer call() throws Exception {
        PathService paths = pathService(root);
        Path projectBundle = paths.workspaceContext().bundleRoot();
        if (!Files.isDirectory(projectBundle)) {
            throw new IllegalArgumentException("Bundle does not exist. Run build first.");
        }

        Path bundleRoot = output == null ? paths.root().resolve("target/runtime-bundle") : output.toAbsolutePath().normalize();
        if (bundleRoot.equals(projectBundle.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Output bundle must be different from the build bundle directory");
        }
        delete(bundleRoot);
        Files.createDirectories(bundleRoot);
        copyIfExists(paths.configRoot(), bundleRoot.resolve("config"));
        copyDirectory(projectBundle, bundleRoot.resolve("bundle"));
        Files.writeString(bundleRoot.resolve(".wiz-spring-bundle"), "workspace=single" + System.lineSeparator());

        System.out.println("Bundle created: " + bundleRoot);
        return 0;
    }

    private PathService pathService(Path root) {
        return WorkspaceRootResolver.pathService(root, "bundle");
    }

    private void copyIfExists(Path source, Path target) throws IOException {
        if (Files.exists(source)) {
            copyDirectory(source, target);
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path item : paths.toList()) {
                Path relative = source.relativize(item);
                Path destination = target.resolve(relative.toString()).normalize();
                if (!destination.startsWith(target.normalize())) {
                    throw new IllegalArgumentException("Bundle copy escapes target directory");
                }
                if (Files.isSymbolicLink(item)) {
                    throw new IllegalArgumentException("Symbolic links are not allowed in bundles: " + relative);
                }
                if (Files.isDirectory(item)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(item, destination);
                }
            }
        }
    }

    private void delete(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}
