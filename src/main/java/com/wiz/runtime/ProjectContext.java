package com.wiz.runtime;

import java.nio.file.Path;

public record ProjectContext(
        String name,
        String packageRoot,
        Path root,
        Path sourceRoot,
        Path appRoot,
        Path modelRoot,
        Path routeRoot,
        Path assetsRoot,
        Path configRoot,
        Path buildRoot,
        Path bundleRoot) {

    public Path bundleWwwRoot() {
        return bundleRoot.resolve("www");
    }

    public Path bundleAssetsRoot() {
        return bundleRoot.resolve("src/assets");
    }
}
