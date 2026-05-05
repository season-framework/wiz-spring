package com.wiz.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wiz.domain.ModelAccessor;
import com.wiz.domain.ModelRegistry;
import com.wiz.session.AuthService;
import com.wiz.session.SessionService;

public class WizContext implements AutoCloseable {

    private final WizRequest request;
    private final WizResponse response;
    private final ProjectContext project;
    private final ConfigService config;
    private final SessionService session;
    private final AuthService auth;
    private final ModelRegistry models;
    private final Map<String, Object> modelRegistry;
    private final List<Runnable> cleanupHooks;

    public WizContext(WizRequest request, WizResponse response, ProjectContext project) {
        this(request, response, project, new ModelRegistry());
    }

    public WizContext(WizRequest request, WizResponse response, ProjectContext project, ModelRegistry models) {
        this.request = request;
        this.response = response;
        this.project = project;
        this.models = models;
        this.modelRegistry = new LinkedHashMap<>();
        this.cleanupHooks = new ArrayList<>();
        this.config = new ConfigService(project);
        this.session = ProjectExtensionLoader.session(this, request.httpSession());
        this.auth = ProjectExtensionLoader.auth(this);
    }

    public WizRequest request() {
        return request;
    }

    public WizResponse response() {
        return response;
    }

    public ProjectContext project() {
        return project;
    }

    public ConfigService config() {
        return config;
    }

    public SessionService session() {
        return session;
    }

    public AuthService auth() {
        return auth;
    }

    public ModelAccessor models() {
        return new ModelAccessor(this, models);
    }

    public Map<String, Object> modelRegistry() {
        return modelRegistry;
    }

    public WizContext onCleanup(Runnable cleanupHook) {
        cleanupHooks.add(cleanupHook);
        return this;
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (Runnable cleanupHook : cleanupHooks.reversed()) {
            try {
                cleanupHook.run();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        cleanupHooks.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
