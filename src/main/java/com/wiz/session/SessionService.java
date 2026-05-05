package com.wiz.session;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpSession;

public class SessionService {

    private final HttpSession httpSession;
    private final Map<String, Object> fallback;

    public SessionService(HttpSession httpSession) {
        this.httpSession = httpSession;
        this.fallback = httpSession == null ? new LinkedHashMap<>() : null;
    }

    public boolean has(String key) {
        return get(key).isPresent();
    }

    public Optional<Object> get(String key) {
        if (httpSession == null) {
            return Optional.ofNullable(fallback.get(key));
        }
        return Optional.ofNullable(httpSession.getAttribute(key));
    }

    public Object get(String key, Object defaultValue) {
        return get(key).orElse(defaultValue);
    }

    public void set(String key, Object value) {
        if (value == null) {
            delete(key);
            return;
        }
        if (httpSession == null) {
            fallback.put(key, value);
            return;
        }
        httpSession.setAttribute(key, value);
    }

    public void set(Map<String, ?> values) {
        values.forEach(this::set);
    }

    public void delete(String key) {
        if (httpSession == null) {
            fallback.remove(key);
            return;
        }
        httpSession.removeAttribute(key);
    }

    public void clear() {
        if (httpSession == null) {
            fallback.clear();
            return;
        }
        Enumeration<String> names = httpSession.getAttributeNames();
        while (names.hasMoreElements()) {
            httpSession.removeAttribute(names.nextElement());
        }
    }

    public void invalidate() {
        clear();
        if (httpSession != null) {
            httpSession.invalidate();
        }
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        if (httpSession == null) {
            values.putAll(fallback);
        } else {
            Enumeration<String> names = httpSession.getAttributeNames();
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                values.put(name, httpSession.getAttribute(name));
            }
        }
        return Collections.unmodifiableMap(values);
    }

    public Optional<String> userId() {
        return get("id").map(Object::toString).filter(value -> !value.isBlank());
    }
}