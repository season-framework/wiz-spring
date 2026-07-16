package com.wiz.runtime;

public final class WizSpringVersion {

    private static final String DEVELOPMENT_VERSION = "dev";

    private WizSpringVersion() {
    }

    public static String current() {
        String version = WizSpringVersion.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? DEVELOPMENT_VERSION : version.trim();
    }
}
