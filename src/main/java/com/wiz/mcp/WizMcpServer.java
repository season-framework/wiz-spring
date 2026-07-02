package com.wiz.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public class WizMcpServer {

    private static final String DEFAULT_PROTOCOL_VERSION = "2024-11-05";

    private final WizMcpToolService tools;
    private final BufferedReader input;
    private final PrintWriter output;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WizMcpServer(WizMcpToolService tools, InputStream input, OutputStream output) {
        this.tools = tools;
        this.input = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.output = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true);
    }

    public void run() throws IOException {
        String line;
        while ((line = input.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            handleLine(line);
        }
    }

    void handleLine(String line) throws IOException {
        Object id = null;
        try {
            Map<String, Object> request = objectMapper.readValue(line, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            id = request.get("id");
            Map<String, Object> response = handleRequest(request);
            if (response != null) {
                send(response);
            }
        } catch (Exception exception) {
            if (id != null) {
                send(error(id, -32700, exception.getMessage()));
            }
        }
    }

    private Map<String, Object> handleRequest(Map<String, Object> request) {
        Object id = request.get("id");
        String method = string(request.get("method"));
        Map<String, Object> params = asMap(request.get("params"));

        if (method == null || method.isBlank()) {
            return id == null ? null : error(id, -32600, "Missing method");
        }

        try {
            return switch (method) {
                case "initialize" -> result(id, initializeResult(params));
                case "notifications/initialized", "notifications/cancelled" -> null;
                case "ping", "logging/setLevel" -> result(id, Map.of());
                case "tools/list" -> result(id, Map.of("tools", tools.toolDefinitions()));
                case "tools/call" -> result(id, callTool(params));
                case "resources/list" -> result(id, Map.of("resources", java.util.List.of()));
                case "resources/read" -> result(id, Map.of("contents", java.util.List.of()));
                case "prompts/list" -> result(id, Map.of("prompts", java.util.List.of()));
                default -> id == null ? null : error(id, -32601, "Method not found: " + method);
            };
        } catch (Exception exception) {
            return id == null ? null : error(id, -32603, exception.getMessage());
        }
    }

    private Map<String, Object> initializeResult(Map<String, Object> params) {
        String protocolVersion = string(params.get("protocolVersion"));
        if (protocolVersion == null || protocolVersion.isBlank()) {
            protocolVersion = DEFAULT_PROTOCOL_VERSION;
        }
        return Map.of(
                "protocolVersion", protocolVersion,
                "capabilities", Map.of("tools", Map.of(), "resources", Map.of()),
                "serverInfo", Map.of("name", "wiz-spring-mcp-server", "version", "3.0.0"));
    }

    private Map<String, Object> callTool(Map<String, Object> params) {
        String name = string(params.get("name"));
        Map<String, Object> arguments = asMap(params.get("arguments"));
        if (name == null || name.isBlank()) {
            return tools.errorResult("Tool name is required");
        }
        try {
            return tools.callTool(name, arguments);
        } catch (Exception exception) {
            return tools.errorResult("Error: " + exception.getMessage());
        }
    }

    private void send(Map<String, Object> message) throws IOException {
        output.println(objectMapper.writeValueAsString(message));
        output.flush();
    }

    private Map<String, Object> result(Object id, Object result) {
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message == null ? "Unknown error" : message));
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private String string(Object value) {
        return value == null ? null : value.toString();
    }
}
