package com.example.helper_bot_oly.config;

import com.pengrad.telegrambot.TelegramBot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TelegramBotConfig {

    @Value("${telegram.bot.token:}")
    private String token;

    @Bean
    public TelegramBot telegramBot() {
        String normalized = token == null ? "" : token.trim();
        if (normalized.isBlank()) {
            // Bootstrap-only client. The listener will not start polling until a real token is configured.
            return new TelegramBot("0:oly-bootstrap-disabled");
        }
        return new TelegramBot(normalized);
    }
}
