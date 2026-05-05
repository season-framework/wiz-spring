package com.wiz.portal;

import com.wiz.domain.ModelProvider;
import com.wiz.runtime.WizContext;

import org.springframework.stereotype.Service;

@Service
public class SmtpModelProvider implements ModelProvider<SmtpService> {

    @Override
    public String namespace() {
        return SeasonPortalModule.SMTP_MODEL;
    }

    @Override
    public Class<SmtpService> type() {
        return SmtpService.class;
    }

    @Override
    public SmtpService create(WizContext context) {
        return new SmtpService(context.config().get("season", com.wiz.session.SeasonConfig.class));
    }
}