package com.wiz.openapi;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Gives springdoc's UI resources precedence over WIZ's intentional {@code /**}
 * MVC fallback without changing the order of any other static resources.
 */
final class SwaggerUiResourceHandlerMapping implements HandlerMapping, Ordered {

    private static final String SWAGGER_WEBJAR_PATH = "/webjars/swagger-ui/";

    private final HandlerMapping resources;
    private final String swaggerUiPath;

    SwaggerUiResourceHandlerMapping(HandlerMapping resources, String configuredUiPath) {
        this.resources = resources;
        this.swaggerUiPath = swaggerUiResourcePath(configuredUiPath);
    }

    @Override
    public HandlerExecutionChain getHandler(HttpServletRequest request) throws Exception {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (!path.startsWith(swaggerUiPath) && !path.startsWith(SWAGGER_WEBJAR_PATH)) {
            return null;
        }
        return resources.getHandler(request);
    }

    @Override
    public int getOrder() {
        return -1;
    }

    private String swaggerUiResourcePath(String configuredUiPath) {
        String path = configuredUiPath == null || configuredUiPath.isBlank() ? "/swagger-ui.html" : configuredUiPath.trim();
        int lastSlash = path.lastIndexOf('/');
        String parent = lastSlash <= 0 ? "" : path.substring(0, lastSlash);
        return parent + "/swagger-ui/";
    }
}
