package com.wiz.security;

public final class SecretMasker {

    private SecretMasker() {
    }

    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replaceAll("(?i)(https?://)([^/@\\s:]+):([^/@\\s]+)@", "$1***:***@")
                .replaceAll("(?i)(https?://)([^/@\\s]+)@", "$1***@")
                .replaceAll("(?i)\\b(token|secret|password|passwd|api[_-]?key)=([^\\s&]+)", "$1=***");
    }
}