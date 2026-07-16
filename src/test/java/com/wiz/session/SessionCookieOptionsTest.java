package com.wiz.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.SessionCookieConfig;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;

class SessionCookieOptionsTest {

    @Test
    void readsTheEffectiveServletSessionCookieConfiguration() {
        MockServletContext servletContext = new MockServletContext();
        SessionCookieConfig config = servletContext.getSessionCookieConfig();
        config.setName("WIZSESSION");
        config.setDomain("example.test");
        config.setPath("/app");
        config.setHttpOnly(true);
        config.setSecure(true);
        config.setAttribute("SameSite", "Strict");
        config.setAttribute("Partitioned", "true");

        SessionCookieOptions options = SessionCookieOptions.from(new MockHttpSession(servletContext));

        assertEquals("WIZSESSION", options.name());
        assertEquals("example.test", options.domain());
        assertEquals("/app", options.path());
        assertTrue(options.httpOnly());
        assertTrue(options.secure());
        assertEquals("Strict", options.sameSite());
        assertTrue(options.partitioned());
    }

    @Test
    void fallsBackToStandardServletCookieValuesWithoutAnHttpSession() {
        SessionCookieOptions options = SessionCookieOptions.from(null);

        assertEquals("JSESSIONID", options.name());
        assertNull(options.domain());
        assertEquals("/", options.path());
        assertTrue(options.httpOnly());
        assertFalse(options.secure());
        assertEquals("Lax", options.sameSite());
        assertFalse(options.partitioned());
    }
}
