package com.wiz.http;

import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Runtime")
public class SmokeController {

    @GetMapping("/smoke")
    @Operation(summary = "Check that the WIZ Spring HTTP runtime is responding")
    @ApiResponse(responseCode = "200", description = "Runtime is available")
    public Map<String, Object> smoke() {
        return Map.of("code", 200, "data", Map.of("runtime", "spring"));
    }
}
