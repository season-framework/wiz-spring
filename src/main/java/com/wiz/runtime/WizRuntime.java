package com.wiz.runtime;

import com.wiz.domain.ModelRegistry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class WizRuntime {

    public static final String DEVMODE_HEADER = "X-Wiz-Devmode";
    public static final String BUILD_MARKER_HEADER = "X-Wiz-Build";

    private final ProjectRegistry projectRegistry;
    private final ModelRegistry modelRegistry;
    private final BuildMarkerService buildMarkerService = new BuildMarkerService();

    @Autowired
    public WizRuntime(ProjectRegistry projectRegistry, ModelRegistry modelRegistry) {
        this.projectRegistry = projectRegistry;
        this.modelRegistry = modelRegistry;
    }

    public WizRuntime(ProjectRegistry projectRegistry) {
        this.projectRegistry = projectRegistry;
        this.modelRegistry = new ModelRegistry();
    }

    public WizContext createContext(WizRequest request) {
        ProjectContext project = projectRegistry.currentProject(request.cookies());
        WizResponse response = new WizResponse();
        if (projectRegistry.devMode(request.cookies())) {
            response.header(DEVMODE_HEADER, "true");
            buildMarkerService.debugHeader(project).ifPresent(value -> response.header(BUILD_MARKER_HEADER, value));
        }
        return new WizContext(request, response, project, modelRegistry);
    }
}