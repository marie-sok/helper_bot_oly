package com.example.helper_bot_oly.controller;

import com.example.helper_bot_oly.service.GruGithubWatchService;
import com.example.helper_bot_oly.service.OlyAiService;
import com.example.helper_bot_oly.service.TelegramWebhookService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final OlyAiService olyAiService;
    private final GruGithubWatchService gruGithubWatchService;
    private final TelegramWebhookService telegramWebhookService;

    public HealthController(
            OlyAiService olyAiService,
            GruGithubWatchService gruGithubWatchService,
            TelegramWebhookService telegramWebhookService
    ) {
        this.olyAiService = olyAiService;
        this.gruGithubWatchService = gruGithubWatchService;
        this.telegramWebhookService = telegramWebhookService;
    }

    @GetMapping({"/", "/health"})
    public Map<String, Object> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "ok");
        status.put("service", "helper_bot_oly");
        status.put("aiAvailable", olyAiService.isAvailable());
        status.put("model", olyAiService.getModel());
        status.put("githubWebhookConfigured", gruGithubWatchService.isWebhookConfigured());
        status.put("telegramTransport", "webhook");
        status.put("telegramWebhookConfigured", telegramWebhookService.isConfigured());
        status.put("telegramWebhookRegistered", telegramWebhookService.isRegistered());
        status.put("timestamp", Instant.now().toString());
        return status;
    }

    @GetMapping("/github/webhook")
    public Map<String, Object> githubWebhookStatus() {
        return Map.of(
                "ok", true,
                "endpoint", "/github/webhook",
                "configured", gruGithubWatchService.isWebhookConfigured(),
                "method", "POST"
        );
    }

    @PostMapping("/github/webhook")
    public ResponseEntity<Map<String, Object>> receiveGithubWebhook(
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventName,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody byte[] body
    ) {
        if (!gruGithubWatchService.isWebhookConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("ok", false, "error", "GITHUB_WEBHOOK_SECRET is not configured"));
        }

        if (!gruGithubWatchService.verifySignature(body, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "error", "Invalid webhook signature"));
        }

        try {
            int notifications = gruGithubWatchService.handleEvent(
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

    @GetMapping("/telegram/webhook")
    public Map<String, Object> telegramWebhookStatus() {
        return Map.of(
                "ok", true,
                "endpoint", "/telegram/webhook",
                "configured", telegramWebhookService.isConfigured(),
                "registered", telegramWebhookService.isRegistered(),
                "method", "POST"
        );
    }

    @PostMapping("/telegram/webhook")
    public ResponseEntity<Map<String, Object>> receiveTelegramWebhook(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secretToken,
            @RequestBody String body
    ) {
        if (!telegramWebhookService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("ok", false, "error", "Telegram is not configured"));
        }

        if (!telegramWebhookService.verifySecret(secretToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "error", "Invalid Telegram webhook secret"));
        }

        if (!telegramWebhookService.accept(body)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("ok", false, "error", "Invalid Telegram update payload"));
        }

        return ResponseEntity.ok(Map.of("ok", true));
    }
}
