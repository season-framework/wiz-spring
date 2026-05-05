import java.util.Map;

import com.wiz.dispatch.RouteHandler;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;
import com.wiz.runtime.WizSegment;

public final class PortalSeasonAuthRouteHandler implements RouteHandler {

    @Override
    public String routeId() {
        return "portal.season.auth";
    }

    @Override
    public WizResult handle(WizContext wiz, WizSegment segment) {
        String path = wiz.request().path();
        if (wiz.request().match("/auth/check").isPresent()) {
            return wiz.auth().check(wiz);
        }
        if (wiz.request().match("/auth/logout").isPresent()) {
            return wiz.auth().logout(wiz);
        }
        if (path.equals("/auth/oidc") || path.startsWith("/auth/oidc/")) {
            return wiz.auth().oidcPlaceholder(wiz);
        }
        if (path.equals("/auth/saml") || path.startsWith("/auth/saml/")) {
            return wiz.auth().samlPlaceholder(wiz);
        }
        return wiz.response().status(404, Map.of("error", "auth route not found"));
    }
}
