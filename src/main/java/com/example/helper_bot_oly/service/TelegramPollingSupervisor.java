package com.example.helper_bot_oly.service;

import com.pengrad.telegrambot.TelegramBot;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class TelegramPollingSupervisor {

    private static final Logger logger = LoggerFactory.getLogger(TelegramPollingSupervisor.class);
    private static final long RESTART_DELAY_SECONDS = 8;

    private final TelegramBot telegramBot;
    private final TelegramBotUpdatesListener updatesListener;
    private final AtomicBoolean restartScheduled = new AtomicBoolean(false);
    private final ScheduledExecutorService restartExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "oly-telegram-polling-restart");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${telegram.bot.token:}")
    private String telegramToken;

    public TelegramPollingSupervisor(
            TelegramBot telegramBot,
            TelegramBotUpdatesListener updatesListener
    ) {
        this.telegramBot = telegramBot;
        this.updatesListener = updatesListener;
    }

    @PostConstruct
    public void init() {
        if (!isTelegramConfigured()) {
            return;
        }

        installListener("startup supervisor");
    }

    private synchronized void installListener(String reason) {
        if (!isTelegramConfigured()) {
            return;
        }

        try {
            telegramBot.removeGetUpdatesListener();
        } catch (Exception e) {
            logger.debug("Telegram polling cleanup before restart was not needed: {}", e.getMessage());
        }

        telegramBot.setUpdatesListener(updatesListener, exception -> {
            if (exception.response() != null) {
                logger.warn(
                        "Telegram polling failed: code={}, description={}. A controlled restart will be attempted.",
                        exception.response().errorCode(),
                        exception.response().description()
                );
            } else {
                logger.warn(
                        "Telegram polling failed: {}. A controlled restart will be attempted.",
                        exception.getMessage()
                );
            }
            scheduleRestart();
        });

        logger.info("Telegram polling listener installed ({})", reason);
    }

    private void scheduleRestart() {
        if (!isTelegramConfigured() || !restartScheduled.compareAndSet(false, true)) {
            return;
        }

        try {
            telegramBot.removeGetUpdatesListener();
        } catch (Exception e) {
            logger.debug("Telegram polling cleanup after failure was not needed: {}", e.getMessage());
        }

        logger.warn("Telegram polling restart scheduled in {} seconds", RESTART_DELAY_SECONDS);

        restartExecutor.schedule(() -> {
            restartScheduled.set(false);
            try {
                installListener("automatic recovery");
                logger.info("Telegram polling recovered after previous failure");
            } catch (Exception e) {
                logger.error("Telegram polling restart attempt failed", e);
                scheduleRestart();
            }
        }, RESTART_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private boolean isTelegramConfigured() {
        return telegramToken != null && !telegramToken.isBlank();
    }

    @PreDestroy
    public void shutdown() {
        restartExecutor.shutdownNow();
        try {
            telegramBot.removeGetUpdatesListener();
        } catch (Exception e) {
            logger.debug("Telegram polling listener was already stopped during shutdown: {}", e.getMessage());
        }
    }
}
