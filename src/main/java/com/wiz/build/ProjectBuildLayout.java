package com.wiz.build;

import java.nio.file.Path;

import com.wiz.runtime.ProjectContext;

public final class ProjectBuildLayout {

    private ProjectBuildLayout() {
    }

    public static Path stagedSourceRoot(ProjectContext project) {
        return targetRoot(project).resolve("work/source");
    }

    public static Path stagedAppRoot(ProjectContext project) {
        return stagedSourceRoot(project).resolve("app");
    }

    public static Path stagedAngularRoot(ProjectContext project) {
        return stagedSourceRoot(project).resolve("angular");
    }

    public static Path stagedAssetsRoot(ProjectContext project) {
        return stagedSourceRoot(project).resolve("assets");
    }

    public static Path stagedControllerRoot(ProjectContext project) {
        return stagedSourceRoot(project).resolve("controller");
    }

    public static Path stagedModelRoot(ProjectContext project) {
        return stagedSourceRoot(project).resolve("model");
    }

    public static Path stagedRouteRoot(ProjectContext project) {
        return stagedSourceRoot(project).resolve("route");
    }

    public static Path generatedJavaSourceRoot(ProjectContext project) {
        return project.buildRoot().resolve("src/main/java");
    }

    public static Path generatedResourcesRoot(ProjectContext project) {
        return project.buildRoot().resolve("src/main/resources");
    }

    public static Path generatedPom(ProjectContext project) {
        return project.buildRoot().resolve("pom.xml");
    }

    public static Path targetRoot(ProjectContext project) {
        return project.buildRoot().resolve("target");
    }

    public static Path classesRoot(ProjectContext project) {
        return targetRoot(project).resolve("classes");
    }

    public static Path appApiJar(ProjectContext project) {
        return targetRoot(project).resolve("app-api.jar");
    }

    public static Path dependencyRoot(ProjectContext project) {
        return targetRoot(project).resolve("dependency");
    }

    public static Path dependencyStagingRoot(ProjectContext project) {
        return targetRoot(project).resolve(".dependency-next");
    }

    public static Path frontendOutputRoot(ProjectContext project) {
        return targetRoot(project).resolve("frontend");
    }

    public static Path frontendDependencyFingerprint(ProjectContext project) {
        return targetRoot(project).resolve("frontend-dependencies.sha256");
    }

    public static Path bundleStagingRoot(ProjectContext project) {
        return targetRoot(project).resolve("work/bundle-next");
    }

    public static Path bundlePreviousRoot(ProjectContext project) {
        return targetRoot(project).resolve("work/bundle-previous");
    }

    public static Path cyclonedxBom(ProjectContext project) {
        return project.bundleRoot().resolve(SupplyChainManifestService.CYCLONEDX_BOM_FILE);
    }
}
