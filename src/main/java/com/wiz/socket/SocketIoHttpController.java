package com.wiz.socket;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@RestController
public class SocketIoHttpController {

    private static final String SEPARATOR = "\u001e";
    private static final long PING_INTERVAL_MS = 25_000;
    private static final long PING_TIMEOUT_MS = 20_000;

    private final ProjectSocketDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final Map<String, PollingSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public SocketIoHttpController(ProjectSocketDispatcher dispatcher) {
        this(dispatcher, new ObjectMapper());
    }

    SocketIoHttpController(ProjectSocketDispatcher dispatcher, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    @GetMapping(path = {"/socket.io/", "/socket.io"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> poll(@RequestParam(name = "sid", required = false) String sid) {
        if (sid == null || sid.isBlank()) {
            PollingSession session = new PollingSession(UUID.randomUUID().toString());
            sessions.put(session.id(), session);
            return ResponseEntity.ok("0" + engineOpen(session.id()));
        }
        PollingSession session = sessions.get(sid);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("unknown sid");
        }
        return ResponseEntity.ok(nextPayload(session));
    }

    @PostMapping(path = {"/socket.io/", "/socket.io"}, consumes = MediaType.ALL_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> receive(@RequestParam(name = "sid", required = false) String sid,
            @RequestBody(required = false) String body) {
        PollingSession session = sid == null ? null : sessions.get(sid);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("unknown sid");
        }
        if (body != null && !body.isBlank()) {
            for (String packet : body.split(SEPARATOR)) {
                handlePacket(session, packet);
            }
        }
        return ResponseEntity.ok("ok");
    }

    private void handlePacket(PollingSession session, String packet) {
        if (packet == null || packet.isBlank() || "3".equals(packet)) {
            return;
        }
        if ("1".equals(packet)) {
            close(session);
            return;
        }
        if ("2".equals(packet)) {
            session.queue().offer("3");
            return;
        }
        if (!packet.startsWith("4")) {
            return;
        }
        handleSocketIoPacket(session, packet.substring(1));
    }

    private void handleSocketIoPacket(PollingSession session, String packet) {
        if (packet.startsWith("0")) {
            connectNamespace(session, packet.substring(1));
        } else if (packet.startsWith("1")) {
            disconnectNamespace(session, packet.substring(1));
        } else if (packet.startsWith("2")) {
            dispatchEvent(session, packet.substring(1));
        }
    }

    private void connectNamespace(PollingSession session, String payload) {
        Optional<SocketNamespace> namespace = namespace(payload);
        if (namespace.isEmpty()) {
            return;
        }
        SocketSession socket = new SocketSession(session.id(), namespace.get());
        SocketEventResult connect = dispatcher.dispatch(socket, "connect", Map.of());
        if (!connect.accepted() && !"socket event handler not found".equals(connect.message())) {
            session.queue().offer("44" + namespacePath(namespace.get()) + "," + errorPayload(connect.message()));
            return;
        }
        session.namespaces().put(namespacePath(namespace.get()), socket);
        session.queue().offer("40" + namespacePath(namespace.get()) + "," + connectPayload(session.id()));
    }

    private void disconnectNamespace(PollingSession session, String payload) {
        namespace(payload).ifPresent(namespace -> {
            SocketSession socket = session.namespaces().remove(namespacePath(namespace));
            if (socket != null) {
                dispatcher.dispatch(socket, "disconnect", Map.of());
                dispatcher.rooms().leaveAll(socket);
            }
        });
    }

    private void dispatchEvent(PollingSession session, String packet) {
        int comma = packet.indexOf(',');
        if (comma < 0) {
            return;
        }
        Optional<SocketNamespace> namespace = SocketNamespace.parse(packet.substring(0, comma));
        if (namespace.isEmpty()) {
            return;
        }
        SocketSession socket = session.namespaces().get(namespacePath(namespace.get()));
        if (socket == null) {
            return;
        }
        String json = packet.substring(comma + 1);
        int arrayStart = json.indexOf('[');
        if (arrayStart > 0) {
            json = json.substring(arrayStart);
        }
        try {
            List<Object> event = objectMapper.readValue(json, new TypeReference<>() {
            });
            if (event.isEmpty()) {
                return;
            }
            String eventName = event.getFirst().toString();
            Object data = event.size() > 1 ? event.get(1) : null;
            sendResult(session, socket, dispatcher.dispatch(socket, eventName, ProjectSocketDispatcher.payload(data)));
        } catch (RuntimeException exception) {
            queueSocketIoEvent(session, namespace.get(), "error", "invalid socket.io message");
        }
    }

    private void sendResult(PollingSession session, SocketSession socket, SocketEventResult result) {
        if (result.room() == null || result.room().isBlank()) {
            queueSocketIoEvent(session, socket.namespace(), result.event(), payload(result.message()));
            return;
        }
        Set<String> recipients = dispatcher.rooms().members(socket.namespace(), result.room());
        if (recipients.isEmpty()) {
            queueSocketIoEvent(session, socket.namespace(), result.event(), payload(result.message()));
            return;
        }
        for (String recipient : recipients) {
            PollingSession target = sessions.get(recipient);
            if (target != null) {
                queueSocketIoEvent(target, socket.namespace(), result.event(), payload(result.message()));
            }
        }
    }

    private void queueSocketIoEvent(PollingSession session, SocketNamespace namespace, String event, Object payload) {
        ArrayList<Object> data = new ArrayList<>();
        data.add(event);
        data.add(payload);
        session.queue().offer("42" + namespacePath(namespace) + "," + objectMapper.writeValueAsString(data));
    }

    private Object payload(String message) {
        if (message == null) {
            return null;
        }
        String trimmed = message.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return message;
        }
        try {
            return objectMapper.readValue(trimmed, new TypeReference<Object>() {
            });
        } catch (RuntimeException exception) {
            return message;
        }
    }

    private Optional<SocketNamespace> namespace(String payload) {
        String namespace = payload == null ? "" : payload.trim();
        int comma = namespace.indexOf(',');
        if (comma >= 0) {
            namespace = namespace.substring(0, comma);
        }
        return SocketNamespace.parse(namespace);
    }

    private String nextPayload(PollingSession session) {
        try {
            String packet = session.queue().poll(PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
            if (packet == null) {
                return "2";
            }
            ArrayList<String> packets = new ArrayList<>();
            packets.add(packet);
            session.queue().drainTo(packets);
            return String.join(SEPARATOR, packets);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "6";
        }
    }

    private void close(PollingSession session) {
        sessions.remove(session.id());
        for (SocketSession socket : session.namespaces().values()) {
            dispatcher.dispatch(socket, "disconnect", Map.of());
            dispatcher.rooms().leaveAll(socket);
        }
        session.namespaces().clear();
    }

    private String engineOpen(String sid) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("sid", sid);
        data.put("upgrades", List.of());
        data.put("pingInterval", PING_INTERVAL_MS);
        data.put("pingTimeout", PING_TIMEOUT_MS);
        data.put("maxPayload", 1_000_000);
        return objectMapper.writeValueAsString(data);
    }

    private String connectPayload(String sid) {
        return objectMapper.writeValueAsString(Map.of("sid", sid));
    }

    private String errorPayload(String message) {
        return objectMapper.writeValueAsString(Map.of("message", message));
    }

    private String namespacePath(SocketNamespace namespace) {
        return namespace.socketIoPath();
    }

    private record PollingSession(String id, BlockingQueue<String> queue, Map<String, SocketSession> namespaces) {

        PollingSession(String id) {
            this(id, new LinkedBlockingQueue<>(), new ConcurrentHashMap<>());
        }
    }
}
