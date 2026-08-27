package __WIZ_PACKAGE_ROOT__.web;

import java.util.Map;

import __WIZ_PACKAGE_ROOT__.api.ApiProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RuntimeConfigurationController {

    private final ApiProperties apiProperties;

    public RuntimeConfigurationController(ApiProperties apiProperties) {
        this.apiProperties = apiProperties;
    }

    @GetMapping("/app-config.json")
    public Map<String, String> configuration() {
        return Map.of("apiPrefix", apiProperties.clientPrefix());
    }
}
