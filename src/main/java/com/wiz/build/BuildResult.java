package com.wiz.build;

import java.util.List;

public record BuildResult(int exitCode, List<String> phases, String message) {

    public boolean success() {
        return exitCode == 0;
    }
}