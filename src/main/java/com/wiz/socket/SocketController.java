package com.wiz.socket;

import java.util.Map;

public interface SocketController {

    String appId();

    Map<String, SocketEventHandler> handlers();
}