package com.example.helper_bot_oly.controller;

import com.example.helper_bot_oly.service.OlyAiService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final OlyAiService olyAiService;

    public HealthController(OlyAiService olyAiService) {
        this.olyAiService = olyAiService;
    }

    @GetMapping({"/", "/health"})
    public Map<String, Object> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "ok");
        status.put("service", "helper_bot_oly");
        status.put("aiAvailable", olyAiService.isAvailable());
        status.put("model", olyAiService.getModel());
        status.put("timestamp", Instant.now().toString());
        return status;
    }
}
