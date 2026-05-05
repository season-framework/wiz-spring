package com.wiz.portal;

import com.wiz.domain.ModelProvider;
import com.wiz.runtime.WizContext;
import com.wiz.session.SeasonConfig;

import org.springframework.stereotype.Service;

@Service
public class PwaModelProvider implements ModelProvider<PwaService> {

    @Override
    public String namespace() {
        return SeasonPortalModule.PWA_MODEL;
    }

    @Override
    public Class<PwaService> type() {
        return PwaService.class;
    }

    @Override
    public PwaService create(WizContext context) {
        return new PwaService(context.project(), context.config().get("season", SeasonConfig.class));
    }
}