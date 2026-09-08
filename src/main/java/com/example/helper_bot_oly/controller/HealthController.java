package com.example.helper_bot_oly.controller;

import com.example.helper_bot_oly.service.GruGithubWatchService;
import com.example.helper_bot_oly.service.OlyAiService;
import com.example.helper_bot_oly.service.TelegramWebhookService;
import org.springframework.beans.factory.ObjectProvider;
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

    private final ObjectProvider<OlyAiService> olyAiServiceProvider;
    private final ObjectProvider<GruGithubWatchService> gruGithubWatchServiceProvider;
    private final ObjectProvider<TelegramWebhookService> telegramWebhookServiceProvider;

    public HealthController(
            ObjectProvider<OlyAiService> olyAiServiceProvider,
            ObjectProvider<GruGithubWatchService> gruGithubWatchServiceProvider,
            ObjectProvider<TelegramWebhookService> telegramWebhookServiceProvider
    ) {
        this.olyAiServiceProvider = olyAiServiceProvider;
        this.gruGithubWatchServiceProvider = gruGithubWatchServiceProvider;
        this.telegramWebhookServiceProvider = telegramWebhookServiceProvider;
    }

    /**
     * Intentionally lightweight. Render can call this endpoint while the expensive
     * JPA/AI graph is still lazy, so Tomcat becomes useful as early as possible.
     */
    @GetMapping({"/", "/health"})
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", "helper_bot_oly",
                "telegramTransport", "webhook",
                "timestamp", Instant.now().toString()
        );
    }

    @GetMapping("/status/deep")
    public Map<String, Object> deepStatus() {
        OlyAiService ai = olyAiServiceProvider.getObject();
        GruGithubWatchService github = gruGithubWatchServiceProvider.getObject();
        TelegramWebhookService telegram = telegramWebhookServiceProvider.getObject();

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "ok");
        status.put("service", "helper_bot_oly");
        status.put("aiAvailable", ai.isAvailable());
        status.put("model", ai.getModel());
        status.put("githubWebhookConfigured", github.isWebhookConfigured());
        status.put("telegramTransport", "webhook");
        status.put("telegramWebhookConfigured", telegram.isConfigured());
        status.put("telegramWebhookRegistered", telegram.isRegistered());
        status.put("timestamp", Instant.now().toString());
        return status;
    }

    @GetMapping("/github/webhook")
    public Map<String, Object> githubWebhookStatus() {
        GruGithubWatchService github = gruGithubWatchServiceProvider.getObject();
        return Map.of(
                "ok", true,
                "endpoint", "/github/webhook",
                "configured", github.isWebhookConfigured(),
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
        GruGithubWatchService github = gruGithubWatchServiceProvider.getObject();
        if (!github.isWebhookConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("ok", false, "error", "GITHUB_WEBHOOK_SECRET is not configured"));
        }

        if (!github.verifySignature(body, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "error", "Invalid webhook signature"));
        }

        try {
            int notifications = github.handleEvent(
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
        TelegramWebhookService telegram = telegramWebhookServiceProvider.getObject();
        return Map.of(
                "ok", true,
                "endpoint", "/telegram/webhook",
                "configured", telegram.isConfigured(),
                "registered", telegram.isRegistered(),
                "method", "POST"
        );
    }

    @PostMapping("/telegram/webhook")
    public ResponseEntity<Map<String, Object>> receiveTelegramWebhook(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secretToken,
            @RequestBody String body
    ) {
        TelegramWebhookService telegram = telegramWebhookServiceProvider.getObject();
        if (!telegram.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("ok", false, "error", "Telegram is not configured"));
        }

        if (!telegram.verifySecret(secretToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "error", "Invalid Telegram webhook secret"));
        }

        if (!telegram.accept(body)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("ok", false, "error", "Invalid Telegram update payload"));
        }

        return ResponseEntity.ok(Map.of("ok", true));
    }
}
