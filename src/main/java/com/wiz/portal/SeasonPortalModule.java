package com.wiz.portal;

import java.util.Map;
import java.util.Optional;

import com.wiz.runtime.ConfigService;
import com.wiz.runtime.PathService;
import com.wiz.runtime.ProjectContext;
import com.wiz.runtime.ProjectRegistry;
import com.wiz.session.SeasonConfig;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SeasonPortalModule {

    public static final String CONFIG_MODEL = "portal/season/config";
    public static final String SESSION_MODEL = "portal/season/session";
    public static final String SMTP_MODEL = "portal/season/smtp";
    public static final String PWA_MODEL = "portal/season/pwa";

    private final ProjectRegistry projectRegistry;

    @Autowired
    public SeasonPortalModule(ProjectRegistry projectRegistry) {
        this.projectRegistry = projectRegistry;
    }

    public SeasonPortalModule(PathService paths) {
        this(new ProjectRegistry(paths));
    }

    public String serviceWorker(Map<String, String> cookies) {
        return currentProject(cookies)
                .map(project -> pwa(project).serviceWorkerScript())
                .orElse("");
    }

    public Optional<Map<String, Object>> manifest(Map<String, String> cookies) {
        return currentProject(cookies).map(project -> pwa(project).manifest());
    }

    public SeasonConfig config(ProjectContext project) {
        return new ConfigService(project).get("season", SeasonConfig.class);
    }

    public PwaService pwa(ProjectContext project) {
        return new PwaService(project, config(project));
    }

    private Optional<ProjectContext> currentProject(Map<String, String> cookies) {
        try {
            return Optional.of(projectRegistry.currentProject(cookies));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return Optional.empty();
        }
    }
}