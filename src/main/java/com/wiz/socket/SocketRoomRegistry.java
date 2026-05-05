package com.wiz.socket;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class SocketRoomRegistry {

    private final Map<String, Set<String>> roomMembers = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sessionRooms = new ConcurrentHashMap<>();

    public void join(SocketSession session, String room) {
        roomMembers.computeIfAbsent(roomKey(session.namespace(), room), ignored -> ConcurrentHashMap.newKeySet()).add(session.id());
        sessionRooms.computeIfAbsent(session.id(), ignored -> ConcurrentHashMap.newKeySet()).add(roomKey(session.namespace(), room));
    }

    public void leave(SocketSession session, String room) {
        String key = roomKey(session.namespace(), room);
        Set<String> members = roomMembers.get(key);
        if (members != null) {
            members.remove(session.id());
        }
        Set<String> rooms = sessionRooms.get(session.id());
        if (rooms != null) {
            rooms.remove(key);
        }
    }

    public void leaveAll(SocketSession session) {
        Set<String> rooms = sessionRooms.remove(session.id());
        if (rooms == null) {
            return;
        }
        rooms.forEach(room -> {
            Set<String> members = roomMembers.get(room);
            if (members != null) {
                members.remove(session.id());
            }
        });
    }

    public boolean contains(SocketNamespace namespace, String room, String sessionId) {
        return roomMembers.getOrDefault(roomKey(namespace, room), Set.of()).contains(sessionId);
    }

    public Set<String> members(SocketNamespace namespace, String room) {
        return Set.copyOf(roomMembers.getOrDefault(roomKey(namespace, room), Set.of()));
    }

    private String roomKey(SocketNamespace namespace, String room) {
        return namespace.project() + ":" + namespace.appId() + ":" + room;
    }
}