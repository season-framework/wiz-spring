package com.wiz.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class WizMcpServerTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void handlesInitializeAndToolsListJsonRpcMessages() throws Exception {
        String input = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        WizMcpServer server = new WizMcpServer(
                new WizMcpToolService(tempDir, null),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                output);

        server.run();

        String[] lines = output.toString(StandardCharsets.UTF_8).strip().split("\\R");
        Map<String, Object> initialize = read(lines[0]);
        Map<String, Object> list = read(lines[1]);
        assertEquals(1, ((Number) initialize.get("id")).intValue());
        assertTrue(((Map<?, ?>) initialize.get("result")).containsKey("serverInfo"));
        assertEquals(2, ((Number) list.get("id")).intValue());
        assertEquals(55, ((java.util.List<?>) ((Map<?, ?>) list.get("result")).get("tools")).size());
    }

    private Map<String, Object> read(String line) throws Exception {
        return objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {
        });
    }
}
