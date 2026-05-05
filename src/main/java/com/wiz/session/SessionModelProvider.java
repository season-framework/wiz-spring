package com.wiz.session;

import com.wiz.domain.ModelProvider;
import com.wiz.runtime.WizContext;

import org.springframework.stereotype.Service;

@Service
public class SessionModelProvider implements ModelProvider<SessionService> {

    @Override
    public String namespace() {
        return "portal/season/session";
    }

    @Override
    public Class<SessionService> type() {
        return SessionService.class;
    }

    @Override
    public SessionService create(WizContext context) {
        return context.session();
    }
}