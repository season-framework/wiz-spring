package com.wiz.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

class SmokeControllerTest {

    @Test
    @SuppressWarnings("unchecked")
    void smokeEndpointReturnsEnvelopeShape() {
        Map<String, Object> response = new SmokeController().smoke();
        Map<String, Object> data = (Map<String, Object>) response.get("data");

        assertEquals(200, response.get("code"));
        assertEquals("spring", data.get("runtime"));
    }
}