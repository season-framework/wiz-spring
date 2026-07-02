package com.wiz.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

public final class PortFinder {

    public static final int MIN_PORT = 0;
    public static final int MAX_PORT = 65535;

    private PortFinder() {
    }

    public static int nextAvailablePort(int startPort) {
        return nextAvailablePort(startPort, null);
    }

    public static int nextAvailablePort(int startPort, String host) {
        validatePort(startPort);
        if (startPort == 0) {
            return 0;
        }
        for (int port = startPort; port <= MAX_PORT; port++) {
            if (isAvailable(port, host)) {
                return port;
            }
        }
        throw new IllegalStateException("No available TCP port at or above " + startPort);
    }

    public static boolean isAvailable(int port, String host) {
        validatePort(port);
        if (port == 0) {
            return true;
        }
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            InetAddress address = bindAddress(host);
            socket.bind(address == null ? new InetSocketAddress(port) : new InetSocketAddress(address, port));
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    public static void validatePort(int port) {
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException("Port must be between 0 and 65535: " + port);
        }
    }

    private static InetAddress bindAddress(String host) throws IOException {
        if (host == null || host.isBlank() || "0.0.0.0".equals(host) || "::".equals(host)) {
            return null;
        }
        return InetAddress.getByName(host);
    }
}
