package com.wiz.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class WizRedirectPropertiesTest {

    @Test
    void defaultPolicyKeepsExistingAnyRedirectBehavior() {
        WizRedirectProperties properties = new WizRedirectProperties();

        assertEquals("https://example.com/after-logout", properties.resolve("https://example.com/after-logout"));
    }

    @Test
    void localOnlyPolicyAllowsLocalPathsAndFallsBackForExternalTargets() {
        WizRedirectProperties properties = new WizRedirectProperties();
        properties.setPolicy(WizRedirectProperties.Policy.LOCAL_ONLY);

        assertEquals("/dashboard", properties.resolve("/dashboard"));
        assertEquals("/", properties.resolve("https://example.com/after-logout"));
        assertEquals("/", properties.resolve("//example.com/after-logout"));
    }

    @Test
    void allowlistPolicyAllowsLocalPathsAndConfiguredHosts() {
        WizRedirectProperties properties = new WizRedirectProperties();
        properties.setPolicy(WizRedirectProperties.Policy.ALLOWLIST);
        properties.setAllowedHosts(List.of("example.com", "https://admin.example.com:8443"));

        assertEquals("/dashboard", properties.resolve("/dashboard"));
        assertEquals("https://example.com/after-logout", properties.resolve("https://example.com/after-logout"));
        assertEquals("https://admin.example.com:8443/after-logout", properties.resolve("https://admin.example.com:8443/after-logout"));
        assertEquals("/", properties.resolve("https://blocked.example.com/after-logout"));
    }
}
