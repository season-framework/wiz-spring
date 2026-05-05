package com.wiz.dispatch;

import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;
import com.wiz.runtime.WizSegment;
import com.wiz.session.AuthService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AuthRouteHandler implements RouteHandler {

    private final AuthService auth;

    @Autowired
    public AuthRouteHandler(AuthService auth) {
        this.auth = auth;
    }

    public AuthRouteHandler() {
        this(new AuthService());
    }

    @Override
    public String routeId() {
        return "portal.season.auth";
    }

    @Override
    public WizResult handle(WizContext context, WizSegment segment) {
        if (context.request().match("/auth/check").isPresent()) {
            return auth.check(context);
        }
        if (context.request().match("/auth/logout").isPresent()) {
            return auth.logout(context);
        }
        String path = context.request().path();
        if (path.equals("/auth/oidc") || path.startsWith("/auth/oidc/")) {
            return auth.oidcPlaceholder(context);
        }
        if (path.equals("/auth/saml") || path.startsWith("/auth/saml/")) {
            return auth.samlPlaceholder(context);
        }
        return context.response().redirect("/");
    }
}