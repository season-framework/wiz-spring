package com.wiz.build;

import java.util.List;

public record FrontendBuildResult(String phase, boolean built, boolean skipped, String message, List<CommandResult> commands) {

    public static FrontendBuildResult skipped(String message) {
        return new FrontendBuildResult("frontend-fallback", false, true, message, List.of());
    }

    public static FrontendBuildResult built(List<CommandResult> commands) {
        return new FrontendBuildResult("frontend-build", true, false, "Generated Angular frontend artifact", List.copyOf(commands));
    }

    public static FrontendBuildResult failed(String message, List<CommandResult> commands) {
        return new FrontendBuildResult("frontend-build", false, false, message, List.copyOf(commands));
    }

    public boolean success() {
        return skipped || built;
    }
}