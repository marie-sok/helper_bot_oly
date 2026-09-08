package com.example.helper_bot_oly.service;

import com.pengrad.telegrambot.BotUtils;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.SetWebhook;
import com.pengrad.telegrambot.response.BaseResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Lazy(false)
public class TelegramWebhookService {

    private static final Logger logger = LoggerFactory.getLogger(TelegramWebhookService.class);
    private static final String DEFAULT_PUBLIC_URL = "https://oly-szm9.onrender.com";

    private final TelegramBot telegramBot;
    private final ObjectProvider<TelegramBotUpdatesListener> updatesListenerProvider;
    private final ExecutorService updateExecutor = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "oly-telegram-webhook-update");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${telegram.bot.token:}")
    private String telegramToken;

    @Value("${oly.public-url:" + DEFAULT_PUBLIC_URL + "}")
    private String publicUrl;

    private volatile boolean registered;

    public TelegramWebhookService(
            TelegramBot telegramBot,
            ObjectProvider<TelegramBotUpdatesListener> updatesListenerProvider
    ) {
        this.telegramBot = telegramBot;
        this.updatesListenerProvider = updatesListenerProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerWebhook() {
        if (!isConfigured()) {
            logger.warn("Telegram webhook registration skipped: TELEGRAM_BOT_TOKEN is not configured");
            return;
        }

        try {
            telegramBot.removeGetUpdatesListener();

            String webhookUrl = normalizedPublicUrl() + "/telegram/webhook";
            BaseResponse response = telegramBot.execute(
                    new SetWebhook()
                            .url(webhookUrl)
                            .secretToken(secretToken())
                            .dropPendingUpdates(false)
                            .allowedUpdates("message")
            );

            registered = response.isOk();
            if (registered) {
                logger.info("Telegram webhook registered: url={}, transport=webhook", webhookUrl);
            } else {
                logger.error("Telegram webhook registration failed: {}", response.description());
            }
        } catch (Exception e) {
            registered = false;
            logger.error("Telegram webhook registration failed", e);
        }
    }

    public boolean isConfigured() {
        return telegramToken != null && !telegramToken.isBlank();
    }

    public boolean isRegistered() {
        return registered;
    }

    public boolean verifySecret(String candidate) {
        if (!isConfigured() || candidate == null || candidate.isBlank()) {
            return false;
        }

        byte[] expected = secretToken().getBytes(StandardCharsets.UTF_8);
        byte[] actual = candidate.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    public boolean accept(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }

        try {
            Update update = BotUtils.parseUpdate(body);
            if (update == null || update.updateId() == null) {
                logger.warn("Telegram webhook payload could not be parsed into an update");
                return false;
            }

            Long chatId = update.message() != null && update.message().chat() != null
                    ? update.message().chat().id()
                    : null;
            logger.info("Telegram webhook accepted: updateId={}, chatId={}", update.updateId(), chatId);

            // Acknowledge the webhook immediately. Heavy JPA/AI services are resolved inside
            // the worker thread so Render cold starts do not hold Telegram's HTTP request open.
            updateExecutor.submit(() -> {
                try {
                    TelegramBotUpdatesListener updatesListener = updatesListenerProvider.getObject();
                    updatesListener.process(List.of(update));
                } catch (Exception e) {
                    logger.error("Telegram webhook update processing failed: updateId={}", update.updateId(), e);
                }
            });
            return true;
        } catch (Exception e) {
            logger.warn("Telegram webhook payload parse failed: {}", e.getMessage());
            return false;
        }
    }

    private String normalizedPublicUrl() {
        String value = publicUrl == null || publicUrl.isBlank() ? DEFAULT_PUBLIC_URL : publicUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String secretToken() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("oly-telegram-webhook:" + telegramToken.trim())
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to derive Telegram webhook secret", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        updateExecutor.shutdownNow();
    }
}
