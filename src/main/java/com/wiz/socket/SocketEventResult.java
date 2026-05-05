package com.wiz.socket;

public record SocketEventResult(boolean accepted, String event, String message) {
}