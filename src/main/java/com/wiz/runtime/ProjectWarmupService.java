package com.wiz.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;

import com.wiz.config.WizRuntimeProperties;
import com.wiz.config.WizRedirectProperties;
import com.wiz.core.ProjectJavaNaming;
import com.wiz.domain.ModelRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

@Service
public class ProjectWarmupService implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectWarmupService.class);

    private final ProjectRegistry projectRegistry;
    private final ProjectRuntimeCache runtimeCache;
    private final ModelRegistry modelRegistry;
    private final WizRedirectProperties redirectProperties;
    private final WizRuntimeProperties runtimeProperties;
    private final ProjectObservabilityRegistry observability;

    @Autowired
    public ProjectWarmupService(
            ProjectRegistry projectRegistry,
            ProjectRuntimeCache runtimeCache,
            ModelRegistry modelRegistry,
            WizRedirectProperties redirectProperties,
            WizRuntimeProperties runtimeProperties,
            ProjectObservabilityRegistry observability) {
        this.projectRegistry = projectRegistry;
        this.runtimeCache = runtimeCache == null ? new ProjectRuntimeCache() : runtimeCache;
        this.modelRegistry = modelRegistry == null ? new ModelRegistry(this.runtimeCache) : modelRegistry;
        this.redirectProperties = redirectProperties == null ? new WizRedirectProperties() : redirectProperties;
        this.runtimeProperties = runtimeProperties == null ? new WizRuntimeProperties() : runtimeProperties;
        this.observability = observability == null ? new ProjectObservabilityRegistry() : observability;
    }

    public ProjectWarmupService(
            ProjectRegistry projectRegistry,
            ProjectRuntimeCache runtimeCache,
            ModelRegistry modelRegistry,
            WizRedirectProperties redirectProperties,
            WizRuntimeProperties runtimeProperties) {
        this(projectRegistry, runtimeCache, modelRegistry, redirectProperties, runtimeProperties, new ProjectObservabilityRegistry());
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!runtimeProperties.isWarmupEnabled()) {
            LOGGER.debug("WIZ app warmup is disabled");
            return;
        }

        Optional<ProjectContext> project = defaultWorkspace();
        if (project.isEmpty()) {
            return;
        }
        warmup(project.get());
    }

    boolean warmup(ProjectContext project) {
        try (WizContext context = new WizContext(
                WizRequest.builder().method("GET").path("/__wiz/warmup").build(),
                new WizResponse(),
                project,
                modelRegistry,
                redirectProperties,
                runtimeCache,
                observability)) {
            ProjectRuntimeCache.CachedProjectRuntime runtime = context.projectRuntime();
            ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(runtime.classLoader());
            try {
                for (String className : warmupClassCandidates(project)) {
                    Class<?> type = loadWarmupClass(runtime, className).orElse(null);
                    if (type == null) {
                        continue;
                    }
                    Method warmup = warmupMethod(type).orElse(null);
                    if (warmup == null) {
                        LOGGER.debug("WIZ app warmup hook not found: {}", className);
                        continue;
                    }
                    invokeWarmup(context, warmup);
                    LOGGER.info("WIZ app warmup completed");
                    return true;
                }
                return false;
            } finally {
                Thread.currentThread().setContextClassLoader(previousLoader);
            }
        } catch (InvocationTargetException exception) {
            LOGGER.warn("WIZ app warmup failed", exception.getCause());
            return false;
        } catch (LinkageError error) {
            LOGGER.warn("WIZ app warmup failed", error);
            return false;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.warn("WIZ app warmup failed", exception);
            return false;
        }
    }

    private List<String> warmupClassCandidates(ProjectContext project) {
        String root = ProjectJavaNaming.packageRoot(project);
        return List.of(root + ".application.model.Struct", root + ".model.Struct");
    }

    private Optional<Class<?>> loadWarmupClass(ProjectRuntimeCache.CachedProjectRuntime runtime, String className) {
        try {
            return Optional.of(Class.forName(className, true, runtime.classLoader()));
        } catch (ClassNotFoundException exception) {
            LOGGER.debug("WIZ app warmup hook class not found: {}", className);
            return Optional.empty();
        }
    }

    private Optional<ProjectContext> defaultWorkspace() {
        try {
            return Optional.of(projectRegistry.workspace());
        } catch (RuntimeException exception) {
            LOGGER.debug("WIZ app warmup skipped: workspace is not available");
            return Optional.empty();
        }
    }

    private Optional<Method> warmupMethod(Class<?> type) {
        try {
            Method method = type.getMethod("warmup", WizContext.class);
            if (!Modifier.isStatic(method.getModifiers())) {
                return Optional.empty();
            }
            method.setAccessible(true);
            return Optional.of(method);
        } catch (NoSuchMethodException exception) {
            return Optional.empty();
        }
    }

    private void invokeWarmup(WizContext context, Method warmup) throws ReflectiveOperationException {
        warmup.invoke(null, context);
    }
}
