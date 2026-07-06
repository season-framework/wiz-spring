package com.wiz.runtime;

import com.wiz.config.WizRedirectProperties;
import com.wiz.domain.ModelRegistry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class WizRuntime {

    public static final String DEVMODE_HEADER = "X-Wiz-Devmode";
    public static final String BUILD_MARKER_HEADER = "X-Wiz-Build";

    private final ProjectRegistry projectRegistry;
    private final ModelRegistry modelRegistry;
    private final WizRedirectProperties redirectProperties;
    private final ProjectRuntimeCache runtimeCache;
    private final ProjectObservabilityRegistry observability;
    private final BuildMarkerService buildMarkerService = new BuildMarkerService();

    @Autowired
    public WizRuntime(ProjectRegistry projectRegistry, ModelRegistry modelRegistry, WizRedirectProperties redirectProperties, ProjectRuntimeCache runtimeCache, ProjectObservabilityRegistry observability) {
        this.projectRegistry = projectRegistry;
        this.modelRegistry = modelRegistry;
        this.redirectProperties = redirectProperties == null ? new WizRedirectProperties() : redirectProperties;
        this.runtimeCache = runtimeCache == null ? new ProjectRuntimeCache() : runtimeCache;
        this.observability = observability == null ? new ProjectObservabilityRegistry() : observability;
    }

    public WizRuntime(ProjectRegistry projectRegistry, ModelRegistry modelRegistry, WizRedirectProperties redirectProperties, ProjectRuntimeCache runtimeCache) {
        this(projectRegistry, modelRegistry, redirectProperties, runtimeCache, new ProjectObservabilityRegistry());
    }

    public WizRuntime(ProjectRegistry projectRegistry, ModelRegistry modelRegistry, WizRedirectProperties redirectProperties) {
        this(projectRegistry, modelRegistry, redirectProperties, new ProjectRuntimeCache());
    }

    public WizRuntime(ProjectRegistry projectRegistry, ModelRegistry modelRegistry) {
        this(projectRegistry, modelRegistry, new WizRedirectProperties());
    }

    public WizRuntime(ProjectRegistry projectRegistry) {
        this(projectRegistry, new ProjectRuntimeCache(), new WizRedirectProperties());
    }

    public WizRuntime(ProjectRegistry projectRegistry, WizRedirectProperties redirectProperties) {
        this(projectRegistry, new ProjectRuntimeCache(), redirectProperties);
    }

    private WizRuntime(ProjectRegistry projectRegistry, ProjectRuntimeCache runtimeCache, WizRedirectProperties redirectProperties) {
        this(projectRegistry, new ModelRegistry(runtimeCache), redirectProperties, runtimeCache);
    }

    public ProjectRuntimeCache runtimeCache() {
        return runtimeCache;
    }

    public WizContext createContext(WizRequest request) {
        ProjectContext project = projectRegistry.workspace();
        WizResponse response = new WizResponse();
        if (projectRegistry.devMode(request.cookies())) {
            response.header(DEVMODE_HEADER, "true");
            buildMarkerService.debugHeader(project).ifPresent(value -> response.header(BUILD_MARKER_HEADER, value));
        }
        return new WizContext(request, response, project, modelRegistry, redirectProperties, runtimeCache, observability);
    }
}
