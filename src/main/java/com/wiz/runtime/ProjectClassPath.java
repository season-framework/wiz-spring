package com.wiz.runtime;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;

public final class ProjectClassPath {

    private ProjectClassPath() {
    }

    public static URL[] apiUrls(ProjectContext project) throws IOException {
        ArrayList<URL> urls = new ArrayList<>();
        for (Path entry : apiEntries(project)) {
            urls.add(entry.toUri().toURL());
        }
        return urls.toArray(URL[]::new);
    }

    public static List<Path> apiEntries(ProjectContext project) throws IOException {
        LinkedHashSet<Path> entries = new LinkedHashSet<>();
        Path classes = project.bundleRoot().resolve("classes");
        Path jar = project.bundleRoot().resolve("app-api.jar");
        if (Files.isDirectory(classes)) {
            entries.add(classes.toAbsolutePath().normalize());
        }
        if (Files.isRegularFile(jar)) {
            entries.add(jar.toAbsolutePath().normalize());
        }
        entries.addAll(dependencyJars(project));
        return List.copyOf(entries);
    }

    public static List<Path> dependencyJars(ProjectContext project) throws IOException {
        LinkedHashSet<Path> jars = new LinkedHashSet<>();
        addJars(jars, project.bundleRoot().resolve("lib"));
        addJars(jars, project.root().resolve("target/dependency"));
        addJars(jars, project.root().resolve("lib"));
        return List.copyOf(jars);
    }

    private static void addJars(LinkedHashSet<Path> jars, Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path jar : paths
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                jars.add(jar.toAbsolutePath().normalize());
            }
        }
    }
}
