package com.wiz.core;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public final class ProjectJavaNaming {

    private ProjectJavaNaming() {
    }

    public static String packageRoot(String projectName) {
        return "com.wiz.project." + packageSegment(projectName);
    }

    public static String packageSegment(String value) {
        String segment = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (segment.isBlank() || Character.isDigit(segment.charAt(0))) {
            return "p_" + segment;
        }
        return segment;
    }

    public static String className(String value) {
        StringBuilder builder = new StringBuilder();
        String source = value == null ? "" : value;
        for (String part : source.split("[./_-]")) {
            if (!part.isBlank()) {
                builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
            }
        }
        return builder.isEmpty() ? "Generated" : builder.toString();
    }

    public static String appApiHandlerClass(String projectName, String appId) {
        return packageRoot(projectName) + ".api." + className(appId) + "Api";
    }

    public static String appSocketHandlerClass(String projectName, String appId) {
        return packageRoot(projectName) + ".socket." + className(appId) + "SocketController";
    }

    public static String routeHandlerClass(String projectName, String routeId) {
        return packageRoot(projectName) + ".route." + className(routeId) + "RouteHandler";
    }

    public static String controllerHookClass(String projectName, String controllerName) {
        if (controllerName != null && controllerName.startsWith("com.")) {
            return controllerName;
        }
        String normalized = controllerName == null ? "" : controllerName.trim().replace('\\', '/');
        if (normalized.isBlank()) {
            normalized = "base";
        }
        String[] parts = Arrays.stream(normalized.split("/"))
                .filter(part -> !part.isBlank())
                .toArray(String[]::new);
        if (parts.length == 0) {
            return packageRoot(projectName) + ".controller.BaseController";
        }
        String prefix = Arrays.stream(parts, 0, Math.max(0, parts.length - 1))
                .map(ProjectJavaNaming::packageSegment)
                .collect(Collectors.joining("."));
        String packageName = packageRoot(projectName) + ".controller" + (prefix.isBlank() ? "" : "." + prefix);
        return packageName + "." + className(parts[parts.length - 1]) + "Controller";
    }

    public static String componentName(String appId) {
        return className(appId) + "Component";
    }

    public static String selector(String appId) {
        String source = appId == null ? "" : appId.toLowerCase(Locale.ROOT);
        String selector = source.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return "wiz-" + (selector.isBlank() ? "generated" : selector);
    }
}