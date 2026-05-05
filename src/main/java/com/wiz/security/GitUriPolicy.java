package com.wiz.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class GitUriPolicy {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("https", "http", "ssh");
    private static final Pattern SCP_LIKE_GIT_URI = Pattern.compile("^git@[A-Za-z0-9._-]+:[^\\s]+$");

    private GitUriPolicy() {
    }

    public static String validate(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Git URI is required");
        }
        String uri = value.trim();
        if (uri.startsWith("-")) {
            throw new IllegalArgumentException("Git URI must not be interpreted as a git option");
        }
        if (uri.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Git URI must not contain control characters");
        }
        if (uri.startsWith("git@")) {
            if (!SCP_LIKE_GIT_URI.matcher(uri).matches()) {
                throw new IllegalArgumentException("Unsupported git URI format");
            }
            return uri;
        }

        URI parsed = parse(uri);
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException("Unsupported git URI scheme");
        }
        if (parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw new IllegalArgumentException("Git URI host is required");
        }
        if (parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException("Git URI query and fragment are not allowed");
        }
        return uri;
    }

    private static URI parse(String uri) {
        try {
            return new URI(uri);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid git URI", exception);
        }
    }
}