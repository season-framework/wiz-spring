package com.wiz.domain;

import com.wiz.runtime.WizContext;

public class ModelAccessor {

    private final WizContext context;
    private final ModelRegistry registry;

    public ModelAccessor(WizContext context, ModelRegistry registry) {
        this.context = context;
        this.registry = registry;
    }

    public <T> T get(String namespace, Class<T> type) {
        return registry.get(context, namespace, type);
    }
}