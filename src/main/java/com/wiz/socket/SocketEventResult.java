package com.wiz.socket;

public record SocketEventResult(boolean accepted, String event, String message, String room) {

    public SocketEventResult(boolean accepted, String event, String message) {
        this(accepted, event, message, null);
    }
}
