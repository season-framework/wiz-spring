package com.wiz.dispatch;

import java.util.List;

import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.ProjectRuntimeCache;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

@Service
public class RouteRegistry {

    private final ProjectRuntimeCache runtimeCache;

    public RouteRegistry() {
        this(new ProjectRuntimeCache());
    }

    public RouteRegistry(ObjectMapper objectMapper) {
        this(new ProjectRuntimeCache(objectMapper));
    }

    @Autowired
    public RouteRegistry(ProjectRuntimeCache runtimeCache) {
        this.runtimeCache = runtimeCache == null ? new ProjectRuntimeCache() : runtimeCache;
    }

    public List<RouteDefinition> definitions(ProjectContext project) {
        return runtimeCache.get(project).routeDefinitions();
    }
}
