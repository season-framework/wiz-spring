package com.wiz.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import com.wiz.config.WizApiProperties;
import com.wiz.config.WizSocketProperties;
import com.wiz.runtime.ProjectContext;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

record FrontendRuntimeConfig(String apiPrefix, String socketPath, String baseuri) {

    static FrontendRuntimeConfig from(ProjectContext project) {
        Properties properties = properties(project);
        WizApiProperties api = new WizApiProperties();
        WizSocketProperties socket = new WizSocketProperties();
        api.setPrefix(value(properties, "wiz.api.prefix"));
        socket.setPath(value(properties, "wiz.socket.path"));
        return new FrontendRuntimeConfig(api.getPrefix(), socket.getPath(), "/wiz");
    }

    String typescriptModule() {
        return "export const WIZ_BASEURI = " + stringLiteral(baseuri) + ";\n"
                + "export const WIZ_API_PREFIX = " + stringLiteral(apiPrefix) + ";\n"
                + "export const WIZ_SOCKET_PATH = " + stringLiteral(socketPath) + ";\n";
    }

    static String stringLiteral(String value) {
        String escaped = (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "\"" + escaped + "\"";
    }

    private static Properties properties(ProjectContext project) {
        Properties merged = new Properties();
        if (project == null) {
            return merged;
        }
        for (Path config : List.of(
                project.configRoot().resolve("application.yml"),
                project.configRoot().resolve("application.yaml"),
                project.configRoot().resolve("wiz.yml"),
                project.configRoot().resolve("wiz.yaml"),
                project.configRoot().resolve("application-prod.yml"),
                project.configRoot().resolve("application-prod.yaml"))) {
            merged.putAll(yaml(config));
        }
        return merged;
    }

    private static Properties yaml(Path path) {
        if (!Files.isRegularFile(path)) {
            return new Properties();
        }
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new FileSystemResource(path));
        Properties properties = factory.getObject();
        return properties == null ? new Properties() : properties;
    }

    private static String value(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
