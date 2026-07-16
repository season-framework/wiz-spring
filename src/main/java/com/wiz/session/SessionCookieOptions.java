package com.wiz.session;

import java.util.Map;

import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;

public record SessionCookieOptions(
        String name,
        String domain,
        String path,
        boolean httpOnly,
        boolean secure,
        String sameSite,
        boolean partitioned) {

    public static SessionCookieOptions defaults() {
        return new SessionCookieOptions("JSESSIONID", null, "/", true, false, "Lax", false);
    }

    public static SessionCookieOptions from(HttpSession session) {
        if (session == null || session.getServletContext() == null) {
            return defaults();
        }
        ServletContext servletContext = session.getServletContext();
        SessionCookieConfig cookie = servletContext.getSessionCookieConfig();
        String contextPath = servletContext.getContextPath();
        return new SessionCookieOptions(
                value(cookie.getName(), "JSESSIONID"),
                nullable(cookie.getDomain()),
                value(cookie.getPath(), contextPath == null || contextPath.isBlank() ? "/" : contextPath),
                cookie.isHttpOnly(),
                cookie.isSecure(),
                value(attribute(cookie.getAttributes(), "SameSite"), "Lax"),
                Boolean.parseBoolean(value(attribute(cookie.getAttributes(), "Partitioned"), "false")));
    }

    private static String attribute(Map<String, String> attributes, String name) {
        if (attributes == null) {
            return null;
        }
        return attributes.entrySet().stream()
                .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static String value(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
