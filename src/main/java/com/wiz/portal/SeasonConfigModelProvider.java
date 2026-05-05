package com.wiz.portal;

import com.wiz.domain.ModelProvider;
import com.wiz.runtime.WizContext;
import com.wiz.session.SeasonConfig;

import org.springframework.stereotype.Service;

@Service
public class SeasonConfigModelProvider implements ModelProvider<SeasonConfig> {

    @Override
    public String namespace() {
        return SeasonPortalModule.CONFIG_MODEL;
    }

    @Override
    public Class<SeasonConfig> type() {
        return SeasonConfig.class;
    }

    @Override
    public SeasonConfig create(WizContext context) {
        return context.config().get("season", SeasonConfig.class);
    }
}