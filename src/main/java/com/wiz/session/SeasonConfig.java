package com.wiz.session;

import java.util.LinkedHashMap;
import java.util.Map;

import com.wiz.runtime.ConfigNamespace;

public record SeasonConfig(
        String ormBase,
        String pwaTitle,
        String pwaStartUrl,
        String pwaDisplay,
        String pwaBackgroundColor,
        String pwaThemeColor,
        String pwaOrientation,
        String pwaIcon,
        String pwaIcon192,
        String pwaIcon512,
        Object smtpHost,
        Integer smtpPort,
        Object smtpSender,
        Object smtpPassword,
        Object sessionCreate,
        Object sessionUserId,
        String sessionCookieName,
        String sessionCookiePath,
        Boolean sessionCookieHttpOnly,
        Boolean sessionCookieSecure,
        String sessionCookieSameSite,
        Object authLoginUri,
        Object authLogoutUri,
        String authBaseUri,
        Boolean authSamlUse,
        String authSamlEntity,
        String authSamlBasePath,
        Object authSamlAcs,
        String authSamlErrorUri) {

    public static SeasonConfig from(ConfigNamespace namespace) {
        return new SeasonConfig(
                string(namespace, "orm_base"),
                string(namespace, "pwa_title"),
                string(namespace, "pwa_start_url"),
                string(namespace, "pwa_display"),
                string(namespace, "pwa_background_color"),
                string(namespace, "pwa_theme_color"),
                string(namespace, "pwa_orientation"),
                string(namespace, "pwa_icon"),
                string(namespace, "pwa_icon_192"),
                string(namespace, "pwa_icon_512"),
                namespace.get("smtp_host"),
                integer(namespace, "smtp_port"),
                namespace.get("smtp_sender"),
                namespace.get("smtp_password"),
                namespace.get("session_create"),
                namespace.get("session_user_id"),
                string(namespace, "session_cookie_name"),
                string(namespace, "session_cookie_path"),
                bool(namespace, "session_cookie_http_only"),
                bool(namespace, "session_cookie_secure"),
                string(namespace, "session_cookie_same_site"),
                namespace.get("auth_login_uri"),
                namespace.get("auth_logout_uri"),
                string(namespace, "auth_baseuri"),
                bool(namespace, "auth_saml_use"),
                string(namespace, "auth_saml_entity"),
                string(namespace, "auth_saml_base_path"),
                namespace.get("auth_saml_acs"),
                string(namespace, "auth_saml_error_uri"));
    }

    public static Map<String, Object> defaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("orm_base", "db/");
        defaults.put("pwa_title", "WIZ Project");
        defaults.put("pwa_start_url", "/");
        defaults.put("pwa_display", "standalone");
        defaults.put("pwa_background_color", "#6C8DF6");
        defaults.put("pwa_theme_color", "#6C8DF6");
        defaults.put("pwa_orientation", "any");
        defaults.put("pwa_icon", "/assets/portal/season/brand/icon.ico");
        defaults.put("pwa_icon_192", "/assets/portal/season/brand/icon-192.png");
        defaults.put("pwa_icon_512", "/assets/portal/season/brand/icon-512.png");
        defaults.put("smtp_host", null);
        defaults.put("smtp_port", 587);
        defaults.put("smtp_sender", null);
        defaults.put("smtp_password", null);
        defaults.put("session_create", null);
        defaults.put("session_user_id", null);
        defaults.put("session_cookie_name", "JSESSIONID");
        defaults.put("session_cookie_path", "/");
        defaults.put("session_cookie_http_only", true);
        defaults.put("session_cookie_secure", false);
        defaults.put("session_cookie_same_site", "Lax");
        defaults.put("auth_login_uri", null);
        defaults.put("auth_logout_uri", null);
        defaults.put("auth_baseuri", "/auth");
        defaults.put("auth_saml_use", false);
        defaults.put("auth_saml_entity", "season");
        defaults.put("auth_saml_base_path", "config/auth/saml");
        defaults.put("auth_saml_acs", null);
        defaults.put("auth_saml_error_uri", "/");
        return defaults;
    }

    private static String string(ConfigNamespace namespace, String key) {
        Object value = namespace.get(key);
        return value == null ? null : value.toString();
    }

    private static Integer integer(ConfigNamespace namespace, String key) {
        Object value = namespace.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? null : Integer.valueOf(value.toString());
    }

    private static Boolean bool(ConfigNamespace namespace, String key) {
        Object value = namespace.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? null : Boolean.valueOf(value.toString());
    }
}