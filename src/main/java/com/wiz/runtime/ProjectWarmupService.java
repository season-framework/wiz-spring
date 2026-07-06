package com.wiz.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
        String className = ProjectJavaNaming.packageRoot(project) + ".model.Struct";
        ProjectRuntimeCache.CachedProjectRuntime runtime;
        try {
            runtime = runtimeCache.get(project);
        } catch (RuntimeException exception) {
            LOGGER.warn("WIZ app warmup skipped: runtime cache could not be created", exception);
            return false;
        }

        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(runtime.classLoader());
        try {
            Class<?> type = Class.forName(className, true, runtime.classLoader());
            Method warmup = warmupMethod(type).orElse(null);
            if (warmup == null) {
                LOGGER.debug("WIZ app warmup hook not found: {}", className);
                return false;
            }
            invokeWarmup(project, warmup);
            LOGGER.info("WIZ app warmup completed");
            return true;
        } catch (ClassNotFoundException exception) {
            LOGGER.debug("WIZ app warmup hook class not found: {}", className);
            return false;
        } catch (InvocationTargetException exception) {
            LOGGER.warn("WIZ app warmup failed", exception.getCause());
            return false;
        } catch (LinkageError error) {
            LOGGER.warn("WIZ app warmup failed", error);
            return false;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.warn("WIZ app warmup failed", exception);
            return false;
        } finally {
            Thread.currentThread().setContextClassLoader(previousLoader);
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

    private void invokeWarmup(ProjectContext project, Method warmup) throws ReflectiveOperationException {
        try (WizContext context = new WizContext(
                WizRequest.builder().method("GET").path("/__wiz/warmup").build(),
                new WizResponse(),
                project,
                modelRegistry,
                redirectProperties,
                runtimeCache,
                observability)) {
            warmup.invoke(null, context);
        }
    }
}
