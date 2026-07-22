package com.wiz.openapi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wiz.config.WizApiProperties;
import com.wiz.dispatch.RouteDefinition;
import com.wiz.dispatch.RouteRegistry;
import com.wiz.runtime.ProjectRegistry;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.customizers.OpenApiCustomizer;

final class WizOpenApiCustomizer implements OpenApiCustomizer {

    static final String APP_API_TAG = "WIZ App API";
    static final String ROUTE_TAG = "WIZ Routes";

    private static final Logger LOGGER = LoggerFactory.getLogger(WizOpenApiCustomizer.class);
    private static final Pattern WIZ_PARAMETER = Pattern.compile("(?<=/)<(?:(path):)?([A-Za-z][A-Za-z0-9]*)>(?=/|$)");
    private static final Pattern SAFE_OPERATION_ID = Pattern.compile("[^A-Za-z0-9_]");
    private static final List<String> DISPATCHED_METHODS = List.of("GET", "POST");

    private final ProjectRegistry projectRegistry;
    private final RouteRegistry routeRegistry;
    private final WizApiProperties apiProperties;

    WizOpenApiCustomizer(ProjectRegistry projectRegistry, RouteRegistry routeRegistry, WizApiProperties apiProperties) {
        this.projectRegistry = projectRegistry;
        this.routeRegistry = routeRegistry;
        this.apiProperties = apiProperties;
    }

    @Override
    public void customise(OpenAPI openApi) {
        Paths paths = openApi.getPaths();
        if (paths == null) {
            paths = new Paths();
            openApi.setPaths(paths);
        }
        Paths documentPaths = paths;
        addAppApiPaths(documentPaths);
        try {
            routeRegistry.definitions(projectRegistry.workspace()).stream()
                    .sorted(Comparator.comparing(RouteDefinition::route).thenComparing(RouteDefinition::id))
                    .forEach(definition -> addRoute(documentPaths, definition));
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to add WIZ route metadata to the OpenAPI document: {}", exception.getMessage());
        }
    }

    private void addAppApiPaths(Paths paths) {
        String prefix = apiProperties.getPrefix();
        addAppApiPath(paths, prefix + "/{appId}/{function}", false);
        addAppApiPath(paths, prefix + "/{appId}/{function}/{path}", true);
    }

    private void addAppApiPath(Paths paths, String path, boolean withExtraPath) {
        PathItem pathItem = paths.computeIfAbsent(path, ignored -> new PathItem());
        for (String method : DISPATCHED_METHODS) {
            PathItem.HttpMethod httpMethod = PathItem.HttpMethod.valueOf(method);
            if (pathItem.readOperationsMap().containsKey(httpMethod)) {
                continue;
            }
            Operation operation = new Operation()
                    .operationId("wizAppApi" + (withExtraPath ? "WithPath" : "") + titleCase(method))
                    .summary("Dispatch a WIZ App API function" + (withExtraPath ? " with an extra path" : ""))
                    .description("Dispatches the request to a function implemented by the active WIZ App API handler. "
                            + "Request and response schemas depend on that handler.")
                    .addTagsItem(APP_API_TAG)
                    .addParametersItem(pathParameter("appId", "WIZ app identifier.", false))
                    .addParametersItem(pathParameter("function", "App API function name.", false))
                    .responses(dynamicResponses());
            if (withExtraPath) {
                operation.addParametersItem(pathParameter("path", "Remaining handler path; it may contain '/'.", true));
            }
            if ("POST".equals(method)) {
                operation.requestBody(dynamicRequestBody());
            }
            operation.addExtension("x-wiz-dispatcher", "app-api");
            pathItem.operation(httpMethod, operation);
        }
    }

    private void addRoute(Paths paths, RouteDefinition definition) {
        DocumentedPath route = documentedPath(definition.route());
        if (route == null) {
            LOGGER.debug("Skipping invalid WIZ route metadata in OpenAPI document: {}", definition.id());
            return;
        }
        PathItem pathItem = paths.computeIfAbsent(route.path(), ignored -> new PathItem());
        List<String> methods = definition.methods() == null || definition.methods().isEmpty()
                ? DISPATCHED_METHODS
                : definition.methods();
        for (String method : methods) {
            String normalizedMethod = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
            if (!DISPATCHED_METHODS.contains(normalizedMethod)) {
                continue;
            }
            PathItem.HttpMethod httpMethod = PathItem.HttpMethod.valueOf(normalizedMethod);
            if (pathItem.readOperationsMap().containsKey(httpMethod)) {
                continue;
            }
            Operation operation = new Operation()
                    .operationId(routeOperationId(definition, normalizedMethod))
                    .summary(summary(definition))
                    .description("Dynamic WIZ route handled through controller '" + definition.controllerName()
                            + "'. Request and response schemas depend on the route handler.")
                    .addTagsItem(ROUTE_TAG)
                    .responses(dynamicResponses());
            route.parameters().forEach(parameter -> operation.addParametersItem(
                    pathParameter(parameter.name(), parameter.description(), parameter.pathRemainder())));
            if ("POST".equals(normalizedMethod)) {
                operation.requestBody(dynamicRequestBody());
            }
            operation.addExtension("x-wiz-route-id", definition.id());
            operation.addExtension("x-wiz-controller", definition.controllerName());
            pathItem.operation(httpMethod, operation);
        }
    }

    private DocumentedPath documentedPath(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return null;
        }
        String path = configuredPath.trim().split("\\?", 2)[0];
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.indexOf('{') >= 0 || path.indexOf('}') >= 0) {
            return null;
        }

        Matcher matcher = WIZ_PARAMETER.matcher(path);
        StringBuilder converted = new StringBuilder();
        List<RouteParameter> parameters = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int cursor = 0;
        while (matcher.find()) {
            String literal = path.substring(cursor, matcher.start());
            if (literal.indexOf('<') >= 0 || literal.indexOf('>') >= 0) {
                return null;
            }
            String name = matcher.group(2);
            if (!names.add(name)) {
                return null;
            }
            boolean pathRemainder = matcher.group(1) != null;
            converted.append(literal).append('{').append(name).append('}');
            parameters.add(new RouteParameter(name,
                    pathRemainder ? "WIZ path remainder; it may contain '/'." : "WIZ route path segment.",
                    pathRemainder));
            cursor = matcher.end();
        }
        String tail = path.substring(cursor);
        if (tail.indexOf('<') >= 0 || tail.indexOf('>') >= 0) {
            return null;
        }
        converted.append(tail);
        return new DocumentedPath(converted.toString(), List.copyOf(parameters));
    }

    private Parameter pathParameter(String name, String description, boolean pathRemainder) {
        StringSchema schema = new StringSchema();
        schema.pattern(pathRemainder ? ".+" : "[^/]+");
        Parameter parameter = new Parameter()
                .name(name)
                .in("path")
                .required(true)
                .description(description)
                .schema(schema);
        if (pathRemainder) {
            parameter.addExtension("x-wiz-segment-type", "path");
        }
        return parameter;
    }

    private RequestBody dynamicRequestBody() {
        Content content = new Content()
                .addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                        new MediaType().schema(new ObjectSchema()))
                .addMediaType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                        new MediaType().schema(new ObjectSchema()))
                .addMediaType(org.springframework.http.MediaType.TEXT_PLAIN_VALUE,
                        new MediaType().schema(new StringSchema()));
        return new RequestBody()
                .required(false)
                .description("Optional handler-specific request body.")
                .content(content);
    }

    private ApiResponses dynamicResponses() {
        Header requestId = new Header().$ref("#/components/headers/" + WizOpenApiConfiguration.REQUEST_ID_HEADER_COMPONENT);
        ApiResponse response = new ApiResponse()
                .description("Handler-defined response.")
                .addHeaderObject("X-Request-Id", requestId);
        return new ApiResponses().addApiResponse("default", response);
    }

    private String routeOperationId(RouteDefinition definition, String method) {
        String id = SAFE_OPERATION_ID.matcher(definition.id()).replaceAll("_");
        if (id.isBlank() || Character.isDigit(id.charAt(0))) {
            id = "route_" + id;
        }
        String uniqueness = Integer.toUnsignedString((definition.id() + "\n" + definition.route()).hashCode(), 36);
        return "wizRoute_" + id + "_" + uniqueness + "_" + method.toLowerCase(Locale.ROOT);
    }

    private String summary(RouteDefinition definition) {
        return definition.title() == null || definition.title().isBlank()
                ? "WIZ route " + definition.id()
                : definition.title();
    }

    private String titleCase(String value) {
        return value.substring(0, 1) + value.substring(1).toLowerCase(Locale.ROOT);
    }

    private record DocumentedPath(String path, List<RouteParameter> parameters) {
    }

    private record RouteParameter(String name, String description, boolean pathRemainder) {
    }
}
