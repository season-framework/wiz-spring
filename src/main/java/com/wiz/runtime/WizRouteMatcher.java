package com.wiz.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WizRouteMatcher {

    public Optional<WizSegment> match(String pattern, String path) {
        CompiledRoute route = compile(pattern);
        Matcher matcher = route.regex().matcher(normalize(path));
        if (!matcher.matches()) {
            return Optional.empty();
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (String name : route.names()) {
            values.put(name, matcher.group(name));
        }
        return Optional.of(new WizSegment(Map.copyOf(values)));
    }

    private CompiledRoute compile(String pattern) {
        String normalizedPattern = normalize(pattern);
        if ("/".equals(normalizedPattern)) {
            return new CompiledRoute(Pattern.compile("^/$"), List.of());
        }

        StringBuilder regex = new StringBuilder("^");
        List<String> names = new ArrayList<>();
        String[] parts = normalizedPattern.substring(1).split("/");
        for (String part : parts) {
            regex.append('/');
            if (part.startsWith("<") && part.endsWith(">")) {
                SegmentExpression expression = parseExpression(part.substring(1, part.length() - 1));
                names.add(expression.name());
                regex.append("(?<").append(expression.name()).append('>').append(expression.regex()).append(')');
            } else {
                regex.append(Pattern.quote(part));
            }
        }
        regex.append("/?$");
        return new CompiledRoute(Pattern.compile(regex.toString()), names);
    }

    private SegmentExpression parseExpression(String expression) {
        int separator = expression.indexOf(':');
        if (separator < 0) {
            return new SegmentExpression(expression, "[^/]+");
        }

        String type = expression.substring(0, separator);
        String name = expression.substring(separator + 1);
        if ("path".equals(type)) {
            return new SegmentExpression(name, ".+");
        }
        throw new IllegalArgumentException("Unsupported route segment type: " + type);
    }

    private String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.split("\\?", 2)[0];
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record SegmentExpression(String name, String regex) {
    }

    private record CompiledRoute(Pattern regex, List<String> names) {
    }
}