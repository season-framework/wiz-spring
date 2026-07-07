package com.wiz.core;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.wiz.runtime.ProjectContext;

public final class ProjectJavaNaming {

    private ProjectJavaNaming() {
    }

    public static String packageRoot(ProjectContext project) {
        return project.packageRoot();
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

    public static String appApiHandlerClass(ProjectContext project, String appId) {
        return packageRoot(project) + ".web.api." + className(appId) + "Api";
    }

    public static String appSocketHandlerClass(ProjectContext project, String appId) {
        return packageRoot(project) + ".realtime.socket." + className(appId) + "SocketController";
    }

    public static String routeHandlerClass(ProjectContext project, String routeId) {
        return packageRoot(project) + ".web.route." + className(routeId) + "RouteHandler";
    }

    public static String controllerHookClass(ProjectContext project, String controllerName) {
        String configured = controllerName == null ? "" : controllerName.trim();
        if (isQualifiedClassName(configured)) {
            return modernizeProjectPackage(project, configured);
        }
        String normalized = configured.replace('\\', '/');
        if (normalized.isBlank()) {
            normalized = "base";
        }
        String[] parts = Arrays.stream(normalized.split("/"))
                .filter(part -> !part.isBlank())
                .toArray(String[]::new);
        if (parts.length == 0) {
            return packageRoot(project) + ".security.guard.BaseController";
        }
        String prefix = Arrays.stream(parts, 0, Math.max(0, parts.length - 1))
                .map(ProjectJavaNaming::packageSegment)
                .collect(Collectors.joining("."));
        String packageName = packageRoot(project) + ".security.guard" + (prefix.isBlank() ? "" : "." + prefix);
        return packageName + "." + className(parts[parts.length - 1]) + "Controller";
    }

    private static boolean isQualifiedClassName(String value) {
        if (value == null || value.isBlank() || value.contains("/") || value.contains("\\")) {
            return false;
        }
        int lastDot = value.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == value.length() - 1) {
            return false;
        }
        String simpleName = value.substring(lastDot + 1);
        return Character.isUpperCase(simpleName.charAt(0))
                && value.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    }

    public static String modernizeProjectPackage(ProjectContext project, String className) {
        if (className == null || className.isBlank()) {
            return className;
        }
        String root = packageRoot(project);
        return modernizeProjectPackages(root, className.trim());
    }

    public static String modernizeProjectPackages(ProjectContext project, String source) {
        return modernizeProjectPackages(packageRoot(project), source);
    }

    private static String modernizeProjectPackages(String root, String source) {
        if (source == null || source.isBlank() || root == null || root.isBlank()) {
            return source;
        }
        String result = source;
        String quotedRoot = Pattern.quote(root);
        result = result.replaceAll(quotedRoot + "\\.portal\\.([a-zA-Z0-9_]+)\\.model\\.struct\\.", root + ".module.$1.application.service.");
        result = result.replaceAll(quotedRoot + "\\.portal\\.([a-zA-Z0-9_]+)\\.model\\.db\\.", root + ".module.$1.domain.entity.");
        result = result.replaceAll(quotedRoot + "\\.portal\\.([a-zA-Z0-9_]+)\\.model\\.orm\\.", root + ".module.$1.infrastructure.orm.");
        result = result.replaceAll(quotedRoot + "\\.portal\\.([a-zA-Z0-9_]+)\\.model\\.security\\.", root + ".module.$1.security.");
        result = result.replaceAll(quotedRoot + "\\.portal\\.([a-zA-Z0-9_]+)\\.model\\.", root + ".module.$1.application.model.");
        result = result.replace(root + ".model.struct.", root + ".application.service.");
        result = result.replace(root + ".model.db.", root + ".domain.entity.");
        result = result.replace(root + ".model.orm.", root + ".infrastructure.orm.");
        result = result.replace(root + ".model.security.", root + ".security.");
        result = result.replace(root + ".model.", root + ".application.model.");
        result = result.replace(root + ".api.", root + ".web.api.");
        result = result.replace(root + ".socket.", root + ".realtime.socket.");
        result = result.replace(root + ".route.", root + ".web.route.");
        result = result.replace(root + ".controller.", root + ".security.guard.");
        return result;
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
