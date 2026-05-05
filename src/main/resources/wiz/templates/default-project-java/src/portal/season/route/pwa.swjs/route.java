import java.nio.file.Files;
import java.nio.file.Path;

import com.wiz.dispatch.RouteHandler;
import com.wiz.runtime.WizContext;
import com.wiz.runtime.WizResult;
import com.wiz.runtime.WizSegment;

public final class PortalSeasonPwaSwjsRouteHandler implements RouteHandler {

    @Override
    public String routeId() {
        return "portal.season.pwa.swjs";
    }

    @Override
    public WizResult handle(WizContext wiz, WizSegment segment) {
        Path script = wiz.project().configRoot().resolve("pwa/sw.js");
        try {
            String body = Files.isRegularFile(script) ? Files.readString(script) : "";
            return WizResult.entity(200, body)
                    .header("Content-Type", "text/javascript; charset=UTF-8")
                    .header("Cache-Control", "no-cache");
        } catch (java.io.IOException exception) {
            return wiz.response().status(500, java.util.Map.of("error", "service worker load failed"));
        }
    }
}
