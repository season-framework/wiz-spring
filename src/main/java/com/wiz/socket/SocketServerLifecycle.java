package com.wiz.socket;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class SocketServerLifecycle implements SmartLifecycle {

    private final SocketNamespaceRegistry registry;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SocketServerLifecycle(SocketNamespaceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void start() {
        running.set(true);
    }

    @Override
    public void stop() {
        running.set(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return false;
    }

    public SocketNamespaceRegistry registry() {
        return registry;
    }
}