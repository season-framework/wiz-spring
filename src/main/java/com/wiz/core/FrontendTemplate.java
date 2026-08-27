package com.wiz.core;

import java.util.Arrays;
import java.util.List;

public enum FrontendTemplate {

    HTML("html", "Static HTML"),
    JSP("jsp", "Spring MVC with JSP"),
    ANGULAR_WIZ("angular-wiz", "Angular with the embedded WIZ frontend source layout"),
    ANGULAR("angular", "Standard Angular"),
    REACT("react", "React");

    private final String id;
    private final String description;

    FrontendTemplate(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public static FrontendTemplate fromId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Frontend template is required");
        }
        return Arrays.stream(values())
                .filter(template -> template.id.equals(id.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported frontend template '" + id + "'. Supported templates: "
                                + String.join(", ", ids())));
    }

    public static List<String> ids() {
        return Arrays.stream(values()).map(FrontendTemplate::id).toList();
    }
}
