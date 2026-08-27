package com.wiz.cli;

import picocli.CommandLine.IVersionProvider;

public class WizVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        String version = WizVersionProvider.class.getPackage().getImplementationVersion();
        return new String[] { "wiz-spring "
                + (version == null || version.isBlank() ? "dev" : version.trim()) };
    }
}
