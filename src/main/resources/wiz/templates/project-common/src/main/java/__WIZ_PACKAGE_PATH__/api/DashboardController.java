package __WIZ_PACKAGE_ROOT__.api;

import __WIZ_PACKAGE_ROOT__.api.model.DashboardModels.DashboardResponse;
import __WIZ_PACKAGE_ROOT__.service.DashboardService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;

@ApiController("/dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    public DashboardResponse dashboard(HttpServletRequest request) {
        return dashboard.overview(request);
    }
}
