package com.wiz.dispatch;

import java.util.List;
import java.util.Locale;

public record RouteDefinition(String id, String title, String route, String controllerName, List<String> methods, String handlerClass) {

    public RouteDefinition(String id, String title, String route, String controllerName, List<String> methods) {
        this(id, title, route, controllerName, methods, null);
    }

    public boolean acceptsMethod(String method) {
        return methods == null || methods.isEmpty() || methods.contains(method.toUpperCase(Locale.ROOT));
    }
}