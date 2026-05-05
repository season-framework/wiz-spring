package com.wiz.domain;

import com.wiz.runtime.WizContext;

public interface ModelProvider<T> {

    String namespace();

    Class<T> type();

    default ModelLifecycle lifecycle() {
        return ModelLifecycle.REQUEST;
    }

    T create(WizContext context);
}