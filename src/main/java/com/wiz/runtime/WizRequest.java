package com.wiz.runtime;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpSession;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public class WizRequest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String method;
    private final String path;
    private final String remoteAddress;
    private final String language;
    private final String body;
    private final Map<String, List<String>> headers;
    private final Map<String, String> cookies;
    private final Map<String, List<String>> queryParameters;
    private final Map<String, List<String>> formParameters;
    private final Map<String, Object> jsonParameters;
    private final HttpSession session;

    private WizRequest(Builder builder) {
        this.method = builder.method;
        this.path = builder.path;
        this.remoteAddress = builder.remoteAddress;
        this.language = builder.language;
        this.body = builder.body;
        this.headers = immutableListMap(builder.headers);
        this.cookies = Map.copyOf(builder.cookies);
        this.queryParameters = immutableListMap(builder.queryParameters);
        this.formParameters = immutableListMap(builder.formParameters);
        this.jsonParameters = Map.copyOf(builder.jsonParameters);
        this.session = builder.session;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    public String remoteAddress() {
        return remoteAddress;
    }

    public String language() {
        return language;
    }

    public String body() {
        return body;
    }

    public Optional<String> header(String name) {
        return first(headers.get(normalizeHeaderName(name)));
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public Optional<String> cookie(String name) {
        return Optional.ofNullable(cookies.get(name));
    }

    public Map<String, String> cookies() {
        return cookies;
    }

    public HttpSession httpSession() {
        return session;
    }

    public Map<String, String> query() {
        Map<String, String> merged = new LinkedHashMap<>();
        formParameters.forEach((name, values) -> first(values).ifPresent(value -> merged.put(name, value)));
        jsonParameters.forEach((name, value) -> {
            if (value != null) {
                merged.put(name, jsonQueryValue(value));
            }
        });
        queryParameters.forEach((name, values) -> first(values).ifPresent(value -> merged.put(name, value)));
        return Map.copyOf(merged);
    }

    public Optional<String> query(String name) {
        return Optional.ofNullable(query().get(name));
    }

    public String query(String name, String defaultValue) {
        return query(name).orElse(defaultValue);
    }

    public String queryRequired(String name) {
        return query(name).orElseThrow(() -> new WizBadRequestException(
                "Missing required query value: " + name,
                Map.of("error", "missing required query value", "name", name)));
    }

    public Map<String, Object> json() {
        return jsonParameters;
    }

    public Optional<Object> json(String name) {
        return Optional.ofNullable(jsonParameters.get(name));
    }

    public Object json(String name, Object defaultValue) {
        return json(name).orElse(defaultValue);
    }

    public Optional<WizSegment> match(String pattern) {
        return new WizRouteMatcher().match(pattern, path);
    }

    private Optional<String> first(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(values.getFirst());
    }

    private static Map<String, List<String>> immutableListMap(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return Map.copyOf(copy);
    }

    private static String normalizeHeaderName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private String jsonQueryValue(Object value) {
        if (value instanceof String text) {
            return text;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (RuntimeException exception) {
            return value.toString();
        }
    }

    public static class Builder {

        private String method = "GET";
        private String path = "/";
        private String remoteAddress = "127.0.0.1";
        private String language = "en";
        private String body = "";
        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private final Map<String, String> cookies = new LinkedHashMap<>();
        private final Map<String, List<String>> queryParameters = new LinkedHashMap<>();
        private final Map<String, List<String>> formParameters = new LinkedHashMap<>();
        private final Map<String, Object> jsonParameters = new LinkedHashMap<>();
        private HttpSession session;

        public Builder method(String method) {
            this.method = method.toUpperCase(Locale.ROOT);
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder remoteAddress(String remoteAddress) {
            this.remoteAddress = remoteAddress;
            return this;
        }

        public Builder language(String language) {
            this.language = language;
            return this;
        }

        public Builder header(String name, String value) {
            add(headers, normalizeHeaderName(name), value);
            return this;
        }

        public Builder cookie(String name, String value) {
            cookies.put(name, value);
            return this;
        }

        public Builder session(HttpSession session) {
            this.session = session;
            return this;
        }

        public Builder queryParam(String name, String value) {
            add(queryParameters, name, value);
            return this;
        }

        public Builder formParam(String name, String value) {
            add(formParameters, name, value);
            return this;
        }

        public Builder queryString(String queryString) {
            parseUrlEncoded(queryString, queryParameters);
            return this;
        }

        public Builder formUrlEncoded(String body) {
            parseUrlEncoded(body, formParameters);
            return this;
        }

        public Builder body(String body) {
            this.body = body == null ? "" : body;
            return this;
        }

        public Builder jsonBody(String body) {
            body(body);
            if (this.body.isBlank()) {
                return this;
            }
            try {
                Map<String, Object> values = OBJECT_MAPPER.readValue(this.body, new TypeReference<>() {
                });
                jsonParameters.clear();
                jsonParameters.putAll(values);
                return this;
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("Invalid JSON request body", exception);
            }
        }

        public WizRequest build() {
            return new WizRequest(this);
        }

        private void parseUrlEncoded(String encoded, Map<String, List<String>> target) {
            if (encoded == null || encoded.isBlank()) {
                return;
            }
            for (String pair : encoded.split("&")) {
                if (pair.isBlank()) {
                    continue;
                }
                String[] parts = pair.split("=", 2);
                String name = decode(parts[0]);
                String value = parts.length > 1 ? decode(parts[1]) : "";
                add(target, name, value);
            }
        }

        private String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }

        private void add(Map<String, List<String>> target, String name, String value) {
            target.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
    }
}
