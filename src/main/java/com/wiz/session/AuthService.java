package com.wiz.session;

import java.util.Map;

import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    public WizResult check(WizContext context) {
        boolean authenticated = context.session().userId().isPresent();
        return context.response().status(200, Map.of(
                "status", authenticated,
                "session", context.session().toMap()));
    }

    public WizResult logout(WizContext context) {
        String returnTo = context.request().query("returnTo", context.request().query("redirect", "/"));
        String redirectTo = context.redirectProperties().resolve(returnTo);
        SessionCookieOptions cookie = SessionCookieOptions.from(context.request().httpSession());
        context.session().invalidate();
        return context.response()
                .header(HttpHeaders.SET_COOKIE, expiredCookie(cookie).toString())
                .redirect(redirectTo);
    }

    public WizResult oidcPlaceholder(WizContext context) {
        return authProviderPlaceholder(context, "oidc");
    }

    public WizResult samlPlaceholder(WizContext context) {
        return authProviderPlaceholder(context, "saml");
    }

    public WizResult requireUser(WizContext context) {
        if (context.session().userId().isPresent()) {
            return null;
        }
        return context.response().status(401, Map.of("error", "unauthorized"));
    }

    public WizResult requireAdmin(WizContext context) {
        WizResult userResult = requireUser(context);
        if (userResult != null) {
            return userResult;
        }
        Object role = context.session().get("role", "");
        if ("admin".equals(role == null ? "" : role.toString())) {
            return null;
        }
        return context.response().status(401, Map.of("error", "admin required"));
    }

    private ResponseCookie expiredCookie(SessionCookieOptions cookie) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(cookie.name(), "")
                .path(cookie.path())
                .httpOnly(cookie.httpOnly())
                .secure(cookie.secure())
                .partitioned(cookie.partitioned())
                .maxAge(0);
        if (cookie.domain() != null && !cookie.domain().isBlank()) {
            builder.domain(cookie.domain());
        }
        if (cookie.sameSite() != null && !cookie.sameSite().isBlank()) {
            builder.sameSite(cookie.sameSite());
        }
        return builder.build();
    }

    private WizResult authProviderPlaceholder(WizContext context, String provider) {
        return context.response().status(501, Map.of(
                "error", "not implemented",
                "provider", provider,
                "message", "OIDC/SAML integration is a configured extension boundary in the Spring port"));
    }
}
