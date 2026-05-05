package com.wiz.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.wiz.http.ResponseEnvelope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class WizResponseTest {

    @TempDir
    Path tempDir;

    @Test
    void wrapsStatusDataInEnvelope() {
        WizResult result = new WizResponse().status(201, Map.of("id", 1));
        ResponseEnvelope envelope = (ResponseEnvelope) result.entity();

        assertEquals(201, result.httpStatus());
        assertEquals(201, envelope.code());
        assertEquals(Map.of("id", 1), envelope.data());
    }

    @Test
    void keepsInvalidEnvelopeCodeButUsesHttpOk() {
        WizResult result = new WizResponse().status(700, "custom");
        ResponseEnvelope envelope = (ResponseEnvelope) result.entity();

        assertEquals(200, result.httpStatus());
        assertEquals(700, envelope.code());
        assertEquals("custom", envelope.data());
    }

    @Test
    void supportsHeadersCookiesRedirectsAndDownloads() throws Exception {
        Path file = tempDir.resolve("report.txt");
        Files.writeString(file, "ok\n");

        WizResult result = new WizResponse()
                .header("X-WIZ", "yes")
                .cookie("token", "abc")
                .download(file, "report.txt");

        assertEquals(200, result.httpStatus());
        assertInstanceOf(FileSystemResource.class, result.entity());
        assertEquals(java.util.List.of("yes"), result.headers().get("X-WIZ"));
        assertEquals(MediaType.APPLICATION_OCTET_STREAM_VALUE, result.headers().get(HttpHeaders.CONTENT_TYPE).getFirst());
        assertEquals("attachment; filename=\"report.txt\"", result.headers().get(HttpHeaders.CONTENT_DISPOSITION).getFirst());
        assertEquals("token=abc; Path=/; HttpOnly; SameSite=Lax", result.headers().get(HttpHeaders.SET_COOKIE).getFirst());

        WizResult redirect = new WizResponse().deleteCookie("token").redirect("/");
        assertEquals(302, redirect.httpStatus());
        assertEquals(java.util.List.of("/"), redirect.headers().get(HttpHeaders.LOCATION));
        assertEquals("token=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; SameSite=Lax", redirect.headers().get(HttpHeaders.SET_COOKIE).getFirst());
    }
}