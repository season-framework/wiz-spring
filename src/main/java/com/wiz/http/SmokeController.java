package com.wiz.http;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SmokeController {

    @GetMapping("/smoke")
    public Map<String, Object> smoke() {
        return Map.of("code", 200, "data", Map.of("runtime", "spring"));
    }
}