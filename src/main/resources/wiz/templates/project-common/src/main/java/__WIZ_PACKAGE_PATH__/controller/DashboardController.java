package __WIZ_PACKAGE_ROOT__.controller;

import __WIZ_PACKAGE_ROOT__.model.Struct;
import __WIZ_PACKAGE_ROOT__.model.dashboard.DashboardStruct.View;
import __WIZ_PACKAGE_ROOT__.web.ApiController;
import org.springframework.web.bind.annotation.GetMapping;

@ApiController("/dashboard")
public class DashboardController {

    private final Struct struct;

    public DashboardController(Struct struct) {
        this.struct = struct;
    }

    @GetMapping
    public View dashboard() {
        return struct.dashboard().overview();
    }
}
