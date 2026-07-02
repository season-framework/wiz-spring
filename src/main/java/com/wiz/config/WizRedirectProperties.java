package com.wiz.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "wiz.redirect")
public class WizRedirectProperties {

    public enum Policy {
        ANY,
        LOCAL_ONLY,
        ALLOWLIST
    }

    @NotNull
    private Policy policy = Policy.ANY;

    private List<@NotBlank String> allowedHosts = new ArrayList<>();

    public Policy getPolicy() {
        return policy;
    }

    public void setPolicy(Policy policy) {
        this.policy = policy == null ? Policy.ANY : policy;
    }

    public List<String> getAllowedHosts() {
        return List.copyOf(allowedHosts);
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? new ArrayList<>() : new ArrayList<>(allowedHosts);
    }

    public String resolve(String location) {
        if (policy == Policy.ANY) {
            return location == null ? "/" : location;
        }
        String candidate = location == null ? "" : location.trim();
        if (isLocalPath(candidate)) {
            return candidate;
        }
        if (policy == Policy.ALLOWLIST && isAllowedAbsoluteUrl(candidate)) {
            return candidate;
        }
        return "/";
    }

    private boolean isLocalPath(String location) {
        if (location.isBlank() || !location.startsWith("/") || location.startsWith("//") || location.contains("\\") || containsControlCharacter(location)) {
            return false;
        }
        try {
            URI uri = new URI(location);
            return !uri.isAbsolute() && uri.getHost() == null && uri.getRawAuthority() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private boolean isAllowedAbsoluteUrl(String location) {
        URI uri;
        try {
            uri = new URI(location);
        } catch (URISyntaxException exception) {
            return false;
        }
        String scheme = lowercase(uri.getScheme());
        String host = lowercase(uri.getHost());
        if (!uri.isAbsolute() || host == null || (!"http".equals(scheme) && !"https".equals(scheme)) || containsControlCharacter(location)) {
            return false;
        }
        return allowedHostKeys(uri).stream().anyMatch(allowedHostSet()::contains);
    }

    private Set<String> allowedHostSet() {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String allowedHost : allowedHosts) {
            if (allowedHost != null && !allowedHost.isBlank()) {
                values.add(allowedHost.trim().toLowerCase(Locale.ROOT));
            }
        }
        return values;
    }

    private Set<String> allowedHostKeys(URI uri) {
        String scheme = lowercase(uri.getScheme());
        String host = lowercase(uri.getHost());
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add(host);
        if (uri.getPort() >= 0) {
            keys.add(host + ":" + uri.getPort());
        }
        keys.add(scheme + "://" + host);
        if (uri.getPort() >= 0) {
            keys.add(scheme + "://" + host + ":" + uri.getPort());
        }
        return keys;
    }

    private boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private String lowercase(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
