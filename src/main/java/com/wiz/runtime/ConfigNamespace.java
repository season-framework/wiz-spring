package com.wiz.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class ConfigNamespace {

    private final String name;
    private final Map<String, Object> values;

    public ConfigNamespace(String name, Map<String, Object> values) {
        this.name = name;
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public String name() {
        return name;
    }

    public Map<String, Object> values() {
        return values;
    }

    public Optional<Object> find(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public Object get(String key) {
        return values.get(key);
    }
}