package com.wiz.socket;

import java.util.Map;

public interface SocketAuthHelper {

    boolean allowed(SocketNamespace namespace, String event, Map<String, Object> payload);
}