package com.wiz.runtime;

import java.util.Map;
import java.util.Optional;

public record WizSegment(Map<String, String> values) {

    public Optional<String> get(String name) {
        return Optional.ofNullable(values.get(name));
    }

    public String require(String name) {
        return get(name).orElseThrow(() -> new IllegalArgumentException("Missing route segment: " + name));
    }
}