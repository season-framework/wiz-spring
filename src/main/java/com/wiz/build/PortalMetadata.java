package com.wiz.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public record PortalMetadata(
        boolean useApp,
        boolean useWidget,
        boolean useRoute,
        boolean useController,
        boolean useModel,
        boolean useAssets,
        boolean useLibs,
        boolean useStyles) {

    public static PortalMetadata read(Path portalDirectory, ObjectMapper objectMapper) throws IOException {
        Path portalJson = portalDirectory.resolve("portal.json");
        if (!Files.isRegularFile(portalJson)) {
            return empty();
        }
        Map<String, Object> metadata = objectMapper.readValue(Files.readAllBytes(portalJson), new TypeReference<>() {
        });
        return new PortalMetadata(
                enabled(metadata, "use_app"),
                enabled(metadata, "use_widget"),
                enabled(metadata, "use_route"),
                enabled(metadata, "use_controller"),
                enabled(metadata, "use_model"),
                enabled(metadata, "use_assets"),
                enabled(metadata, "use_libs"),
                enabled(metadata, "use_styles"));
    }

    public static PortalMetadata empty() {
        return new PortalMetadata(false, false, false, false, false, false, false, false);
    }

    private static boolean enabled(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof Boolean flag ? flag : value != null && Boolean.parseBoolean(value.toString());
    }
}