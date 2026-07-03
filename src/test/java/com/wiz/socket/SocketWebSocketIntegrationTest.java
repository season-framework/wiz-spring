package com.wiz.socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.wiz.build.BuildResult;
import com.wiz.build.ProjectBuildService;
import com.wiz.core.ProjectService;
import com.wiz.core.WorkspaceService;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SocketWebSocketIntegrationTest {

    private static final Path WORKSPACE = workspace();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("wiz.root", () -> WORKSPACE.toString());
    }

    @Test
    void dispatchesCompiledProjectSocketThroughDirectWebSocketBridgePath() throws Exception {
        ProjectContext project = project();
        Files.writeString(project.appRoot().resolve("page.dashboard/socket.java"), dashboardSocketJava());
        allowSocketWithoutLogin(project, "page.dashboard");
        BuildResult build = new ProjectBuildService().build(project, true, "bundle");
        assertTrue(build.success(), build.message());

        QueueListener listener = new QueueListener();
        WebSocket webSocket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/wiz/app/page.dashboard"), listener)
                .join();

        Map<String, Object> connect = envelope(listener.nextMessage());
        assertEquals("connect", connect.get("event"));
        assertEquals(Boolean.TRUE, connect.get("accepted"));

        webSocket.sendText("{\"event\":\"join\",\"data\":{\"id\":\"room-it\"}}", true).join();
        Map<String, Object> join = envelope(listener.nextMessage());
        assertEquals("join", join.get("event"));
        assertEquals(Boolean.TRUE, join.get("accepted"));
        assertEquals("room-it", join.get("message"));

        webSocket.sendText("{", true).join();
        Map<String, Object> invalid = envelope(listener.nextMessage());
        assertEquals("message", invalid.get("event"));
        assertEquals(Boolean.FALSE, invalid.get("accepted"));
        assertEquals("invalid json message", invalid.get("message"));

        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    void acceptsSocketIoClientStyleNamespaceOverHttpPollingPath() throws Exception {
        ProjectContext project = project();
        Files.writeString(project.appRoot().resolve("page.dashboard/socket.java"), dashboardSocketJava());
        allowSocketWithoutLogin(project, "page.dashboard");
        BuildResult build = new ProjectBuildService().build(project, true, "bundle");
        assertTrue(build.success(), build.message());

        HttpClient client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port + "/socket.io/?EIO=4&transport=polling";
        String open = client.send(HttpRequest.newBuilder(URI.create(base)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(open.startsWith("0"), open);
        Map<String, Object> openPacket = OBJECT_MAPPER.readValue(open.substring(1), new TypeReference<>() {
        });
        String sid = openPacket.get("sid").toString();

        post(client, base + "&sid=" + sid, "40/wiz/app/page.dashboard,");
        String connect = client.send(HttpRequest.newBuilder(URI.create(base + "&sid=" + sid)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(connect.startsWith("40/wiz/app/page.dashboard,"), connect);

        post(client, base + "&sid=" + sid, "42/wiz/app/page.dashboard,[\"join\",{\"id\":\"room-it\"}]");
        String join = client.send(HttpRequest.newBuilder(URI.create(base + "&sid=" + sid)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(join.startsWith("42/wiz/app/page.dashboard,"), join);
        assertTrue(join.contains("\"join\""), join);
        assertTrue(join.contains("\"room-it\""), join);
    }

    @Test
    void broadcastsRoomResultToJoinedWebSocketSessions() throws Exception {
        ProjectContext project = project();
        Files.writeString(project.appRoot().resolve("page.dashboard/socket.java"), broadcastSocketJava());
        allowSocketWithoutLogin(project, "page.dashboard");
        BuildResult build = new ProjectBuildService().build(project, true, "bundle");
        assertTrue(build.success(), build.message());

        QueueListener first = new QueueListener();
        WebSocket firstSocket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/wiz/app/page.dashboard"), first)
                .join();
        QueueListener second = new QueueListener();
        WebSocket secondSocket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/wiz/app/page.dashboard"), second)
                .join();

        assertEquals("connect", envelope(first.nextMessage()).get("event"));
        assertEquals("connect", envelope(second.nextMessage()).get("event"));

        firstSocket.sendText("{\"event\":\"join\",\"data\":{\"id\":\"room-it\"}}", true).join();
        secondSocket.sendText("{\"event\":\"join\",\"data\":{\"id\":\"room-it\"}}", true).join();
        assertEquals("join", envelope(first.nextMessage()).get("event"));
        assertEquals("join", envelope(second.nextMessage()).get("event"));

        firstSocket.sendText("{\"event\":\"send\",\"data\":{\"id\":\"room-it\",\"text\":\"hello room\"}}", true).join();
        Map<String, Object> firstMessage = envelope(first.nextMessage());
        Map<String, Object> secondMessage = envelope(second.nextMessage());
        assertEquals("chat.message", firstMessage.get("event"));
        assertEquals("chat.message", secondMessage.get("event"));
        assertEquals("room-it", firstMessage.get("room"));
        assertTrue(firstMessage.get("message").toString().contains("hello room"));
        assertTrue(secondMessage.get("message").toString().contains("hello room"));

        firstSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        secondSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    private static Map<String, Object> envelope(String message) throws IOException {
        return OBJECT_MAPPER.readValue(message, new TypeReference<>() {
        });
    }

    private static void post(HttpClient client, String uri, String body) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(uri))
                .header("Content-Type", "text/plain;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
    }

    private static void allowSocketWithoutLogin(ProjectContext project, String appId) throws IOException {
        Path appJson = project.appRoot().resolve(appId).resolve("app.json");
        Files.writeString(appJson, Files.readString(appJson).replace("\"controller\": \"user\"", "\"controller\": \"base\""));
    }

    private static ProjectContext project() throws Exception {
        PathService paths = new PathService(WORKSPACE);
        if (Files.isDirectory(WORKSPACE.resolve("src"))) {
            return paths.workspaceContext();
        }
        return new ProjectService(paths).createApp(null, null);
    }

    private static Path workspace() {
        try {
            Path workspace = Files.createTempDirectory("wiz-ws-it").resolve("workspace");
            new WorkspaceService().createWorkspace(workspace);
            return workspace;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String dashboardSocketJava() {
        return "import java.util.Map;\n"
                + "import com.wiz.socket.SocketController;\n"
                + "import com.wiz.socket.SocketEventHandler;\n"
                + "import com.wiz.socket.SocketEventResult;\n"
                + "import com.wiz.socket.SocketRoomRegistry;\n"
                + "import com.wiz.socket.SocketSession;\n\n"
                + "public final class PageDashboardSocketController implements SocketController {\n"
                + "    public String appId() { return \"page.dashboard\"; }\n"
                + "    public Map<String, SocketEventHandler> handlers() {\n"
                + "        return Map.of(\"connect\", this::connect, \"join\", this::join);\n"
                + "    }\n"
                + "    private SocketEventResult connect(SocketSession session, Map<String, Object> payload, SocketRoomRegistry rooms) {\n"
                + "        return new SocketEventResult(true, \"connect\", \"connected\");\n"
                + "    }\n"
                + "    private SocketEventResult join(SocketSession session, Map<String, Object> payload, SocketRoomRegistry rooms) {\n"
                + "        String room = payload.get(\"id\").toString();\n"
                + "        rooms.join(session, room);\n"
                + "        return new SocketEventResult(true, \"join\", room);\n"
                + "    }\n"
                + "}\n";
    }

    private String broadcastSocketJava() {
        return "import java.util.Map;\n"
                + "import com.wiz.socket.SocketController;\n"
                + "import com.wiz.socket.SocketEventHandler;\n"
                + "import com.wiz.socket.SocketEventResult;\n"
                + "import com.wiz.socket.SocketRoomRegistry;\n"
                + "import com.wiz.socket.SocketSession;\n\n"
                + "public final class PageDashboardSocketController implements SocketController {\n"
                + "    public String appId() { return \"page.dashboard\"; }\n"
                + "    public Map<String, SocketEventHandler> handlers() {\n"
                + "        return Map.of(\"connect\", this::connect, \"join\", this::join, \"send\", this::send, \"disconnect\", this::disconnect);\n"
                + "    }\n"
                + "    private SocketEventResult connect(SocketSession session, Map<String, Object> payload, SocketRoomRegistry rooms) {\n"
                + "        return new SocketEventResult(true, \"connect\", \"connected\");\n"
                + "    }\n"
                + "    private SocketEventResult join(SocketSession session, Map<String, Object> payload, SocketRoomRegistry rooms) {\n"
                + "        String room = payload.get(\"id\").toString();\n"
                + "        rooms.join(session, room);\n"
                + "        return new SocketEventResult(true, \"join\", room);\n"
                + "    }\n"
                + "    private SocketEventResult send(SocketSession session, Map<String, Object> payload, SocketRoomRegistry rooms) {\n"
                + "        String room = payload.get(\"id\").toString();\n"
                + "        return new SocketEventResult(true, \"chat.message\", payload.get(\"text\").toString(), room);\n"
                + "    }\n"
                + "    private SocketEventResult disconnect(SocketSession session, Map<String, Object> payload, SocketRoomRegistry rooms) {\n"
                + "        rooms.leaveAll(session);\n"
                + "        return new SocketEventResult(true, \"disconnect\", \"disconnected\");\n"
                + "    }\n"
                + "}\n";
    }

    private static final class QueueListener implements WebSocket.Listener {

        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        String nextMessage() throws InterruptedException {
            String message = messages.poll(10, TimeUnit.SECONDS);
            assertNotNull(message, "expected websocket message");
            return message;
        }
    }
}
