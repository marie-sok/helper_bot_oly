package com.example.helper_bot_oly.controller;

import com.example.helper_bot_oly.service.GruGithubWatchService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/github")
public class GithubWebhookController {

    private final GruGithubWatchService watchService;

    public GithubWebhookController(GruGithubWatchService watchService) {
        this.watchService = watchService;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventName,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody byte[] body
    ) {
        if (!watchService.isWebhookConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("ok", false, "error", "GITHUB_WEBHOOK_SECRET is not configured"));
        }

        if (!watchService.verifySignature(body, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "error", "Invalid webhook signature"));
        }

        try {
            int notifications = watchService.handleEvent(
                    eventName == null ? "" : eventName,
                    deliveryId == null ? "" : deliveryId,
                    body
            );
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "event", eventName == null ? "" : eventName,
                    "notifications", notifications
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("ok", false, "error", "Unable to process webhook payload"));
        }
    }
}
