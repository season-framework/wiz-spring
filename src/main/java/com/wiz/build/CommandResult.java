package com.wiz.build;

import java.nio.file.Path;
import java.util.List;

public record CommandResult(
        String phase,
        List<String> argv,
        Path cwd,
        int exitCode,
        long durationMillis,
        boolean timedOut,
        boolean cappedOutput,
        String output) {

    public boolean success() {
        return !timedOut && exitCode == 0;
    }

    public String summary() {
        return phase + " command=" + String.join(" ", argv)
                + " cwd=" + cwd
                + " exitCode=" + exitCode
                + " durationMillis=" + durationMillis
                + " timedOut=" + timedOut
                + " cappedOutput=" + cappedOutput
                + (output == null || output.isBlank() ? "" : System.lineSeparator() + output.strip());
    }
}