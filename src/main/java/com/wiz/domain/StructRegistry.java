package com.wiz.domain;

import com.wiz.runtime.WizContext;

public class StructRegistry {

    private final ModelRegistry models;

    public StructRegistry(ModelRegistry models) {
        this.models = models;
    }

    public <T> T get(WizContext context, String namespace, Class<T> type) {
        String modelNamespace = namespace.equals("struct") || namespace.startsWith("struct/")
                ? namespace
                : "struct/" + namespace;
        return models.get(context, modelNamespace, type);
    }
}