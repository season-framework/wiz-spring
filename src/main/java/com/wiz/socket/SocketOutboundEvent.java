package com.wiz.socket;

import java.util.Set;

public record SocketOutboundEvent(SocketNamespace namespace, String room, String event, Object payload, Set<String> recipients) {
}