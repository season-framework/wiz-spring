package com.wiz.cli;

import com.wiz.runtime.WizSpringVersion;

import picocli.CommandLine.IVersionProvider;

public class WizVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        return new String[] { "wiz-spring " + WizSpringVersion.current() };
    }
}
