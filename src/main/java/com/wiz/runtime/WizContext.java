package com.wiz.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wiz.config.WizRedirectProperties;
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
    private final WizRedirectProperties redirectProperties;
    private final ProjectRuntimeCache runtimeCache;
    private final ProjectObservabilityRegistry observability;
    private final Map<String, Object> modelRegistry;
    private final List<Runnable> cleanupHooks;
    private volatile ProjectRuntimeCache.CachedProjectRuntime projectRuntime;

    public WizContext(WizRequest request, WizResponse response, ProjectContext project) {
        this(request, response, project, new ProjectRuntimeCache(), new WizRedirectProperties(), new ProjectObservabilityRegistry());
    }

    private WizContext(WizRequest request, WizResponse response, ProjectContext project, ProjectRuntimeCache runtimeCache, WizRedirectProperties redirectProperties, ProjectObservabilityRegistry observability) {
        this(request, response, project, new ModelRegistry(runtimeCache), redirectProperties, runtimeCache, observability);
    }

    public WizContext(WizRequest request, WizResponse response, ProjectContext project, ModelRegistry models) {
        this(request, response, project, models, new WizRedirectProperties());
    }

    public WizContext(WizRequest request, WizResponse response, ProjectContext project, ModelRegistry models, WizRedirectProperties redirectProperties) {
        this(request, response, project, models, redirectProperties, new ProjectRuntimeCache(), new ProjectObservabilityRegistry());
    }

    public WizContext(WizRequest request, WizResponse response, ProjectContext project, ModelRegistry models, WizRedirectProperties redirectProperties, ProjectRuntimeCache runtimeCache) {
        this(request, response, project, models, redirectProperties, runtimeCache, new ProjectObservabilityRegistry());
    }

    public WizContext(WizRequest request, WizResponse response, ProjectContext project, ModelRegistry models, WizRedirectProperties redirectProperties, ProjectRuntimeCache runtimeCache, ProjectObservabilityRegistry observability) {
        this.request = request;
        this.response = response;
        this.project = project;
        this.models = models;
        this.redirectProperties = redirectProperties == null ? new WizRedirectProperties() : redirectProperties;
        this.runtimeCache = runtimeCache == null ? new ProjectRuntimeCache() : runtimeCache;
        this.observability = observability == null ? new ProjectObservabilityRegistry() : observability;
        this.modelRegistry = new LinkedHashMap<>();
        this.cleanupHooks = new ArrayList<>();
        this.config = new ConfigService(project, projectRuntime());
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

    public ProjectContext workspace() {
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

    public WizRedirectProperties redirectProperties() {
        return redirectProperties;
    }

    public ProjectRuntimeCache runtimeCache() {
        return runtimeCache;
    }

    public ProjectRuntimeCache.CachedProjectRuntime projectRuntime() {
        ProjectRuntimeCache.CachedProjectRuntime runtime = projectRuntime;
        if (runtime == null) {
            runtime = runtimeCache.get(project);
            projectRuntime = runtime;
        }
        return runtime;
    }

    public ProjectObservabilityRegistry observability() {
        return observability;
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
        List<Runnable> hooks = new ArrayList<>(cleanupHooks);
        cleanupHooks.clear();
        for (Runnable cleanupHook : hooks.reversed()) {
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
        if (failure != null) {
            throw failure;
        }
    }
}
