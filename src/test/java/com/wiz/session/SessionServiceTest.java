package com.wiz.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class SessionServiceTest {

    @Test
    void storesValuesInHttpSession() {
        MockHttpSession httpSession = new MockHttpSession();
        SessionService session = new SessionService(httpSession);

        session.set(Map.of("id", "u1", "email", "u1@example.com", "role", "admin"));

        assertTrue(session.has("id"));
        assertEquals("u1", session.get("id").orElseThrow());
        assertEquals("admin", httpSession.getAttribute("role"));
        assertEquals("u1@example.com", session.toMap().get("email"));
        assertEquals("u1", session.userId().orElseThrow());

        session.delete("email");
        assertFalse(session.has("email"));
        session.clear();
        assertTrue(session.toMap().isEmpty());
    }

}
