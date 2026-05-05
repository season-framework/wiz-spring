package com.wiz.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;

public class WizResponse {

    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private final Map<String, Object> data = new LinkedHashMap<>();

    public WizResponse data(String name, Object value) {
        data.put(name, value);
        return this;
    }

    public Map<String, Object> data() {
        return Map.copyOf(data);
    }

    public WizResponse header(String name, String value) {
        headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        return this;
    }

    public WizResponse cookie(String name, String value) {
        return cookie(ResponseCookie.from(name, value)
                .path("/")
                .httpOnly(true)
                .sameSite("Lax")
                .build());
    }

    public WizResponse deleteCookie(String name) {
        return cookie(ResponseCookie.from(name, "")
                .path("/")
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(0)
                .build());
    }

    public WizResponse cookie(ResponseCookie cookie) {
        return header(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public WizResult status(int code) {
        return status(code, Map.of());
    }

    public WizResult status(int code, Object data) {
        return apply(WizResult.envelope(code, data));
    }

    public WizResult ok(Object data) {
        return status(200, data);
    }

    public WizResult redirect(String location) {
        return apply(WizResult.redirect(location));
    }

    public WizResult download(Path path) {
        return download(path, path == null || path.getFileName() == null ? "download" : path.getFileName().toString());
    }

    public WizResult download(Path path, String filename) {
        if (path == null || !Files.isRegularFile(path)) {
            return status(404, Map.of("error", "file not found"));
        }
        return apply(WizResult.entity(200, new FileSystemResource(path))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeFilename(filename) + "\"")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE));
    }

    private WizResult apply(WizResult result) {
        WizResult output = result;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            for (String value : entry.getValue()) {
                output = output.header(entry.getKey(), value);
            }
        }
        return output;
    }

    private String safeFilename(String filename) {
        String value = filename == null || filename.isBlank() ? "download" : filename;
        return value.replace("\\", "_").replace("/", "_").replace("\"", "_");
    }
}