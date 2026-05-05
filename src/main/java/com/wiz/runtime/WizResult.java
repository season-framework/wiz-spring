package com.wiz.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wiz.http.ResponseEnvelope;

public record WizResult(int httpStatus, Object entity, Map<String, List<String>> headers) {

    public WizResult(int httpStatus, Object entity) {
        this(httpStatus, entity, Map.of());
    }

    public static WizResult envelope(int code, Object data) {
        return new WizResult(httpStatus(code), new ResponseEnvelope(code, data));
    }

    public static WizResult empty(int httpStatus) {
        return new WizResult(httpStatus(httpStatus), null);
    }

    public static WizResult entity(int httpStatus, Object entity) {
        return new WizResult(httpStatus(httpStatus), entity);
    }

    public static WizResult redirect(String location) {
        return empty(302).header("Location", location);
    }

    public WizResult header(String name, String value) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((key, values) -> copy.put(key, new ArrayList<>(values)));
        copy.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        return new WizResult(httpStatus, entity, immutableHeaders(copy));
    }

    public WizResult {
        headers = immutableHeaders(headers == null ? Map.of() : headers);
    }

    private static Map<String, List<String>> immutableHeaders(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return Map.copyOf(copy);
    }

    private static int httpStatus(int code) {
        return code >= 100 && code <= 599 ? code : 200;
    }
}