package com.wiz.session;

import com.wiz.runtime.ConfigNamespace;

public record SessionCookieOptions(
        String name,
        String path,
        boolean httpOnly,
        boolean secure,
        String sameSite) {

    public static SessionCookieOptions from(ConfigNamespace namespace) {
        return new SessionCookieOptions(
                string(namespace, "session_cookie_name", "JSESSIONID"),
                string(namespace, "session_cookie_path", "/"),
                bool(namespace, "session_cookie_http_only", true),
                bool(namespace, "session_cookie_secure", false),
                string(namespace, "session_cookie_same_site", "Lax"));
    }

    private static String string(ConfigNamespace namespace, String key, String defaultValue) {
        Object value = namespace.get(key);
        return value == null || value.toString().isBlank() ? defaultValue : value.toString();
    }

    private static boolean bool(ConfigNamespace namespace, String key, boolean defaultValue) {
        Object value = namespace.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? defaultValue : Boolean.parseBoolean(value.toString());
    }
}