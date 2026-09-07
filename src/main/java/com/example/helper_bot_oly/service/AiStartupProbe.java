package com.example.helper_bot_oly.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class AiStartupProbe {

    private static final Logger logger = LoggerFactory.getLogger(AiStartupProbe.class);

    @Value("${oly.ai.enabled:true}")
    private boolean enabled;

    @Value("${oly.ai.provider:openai}")
    private String provider;

    @Value("${oly.ai.api-key:}")
    private String openAiKey;

    @Value("${oly.ai.base-url:https://api.openai.com/v1}")
    private String openAiBaseUrl;

    @Value("${oly.ai.model:gpt-5.6-terra}")
    private String openAiModel;

    @Value("${oly.ai.gemini-api-key:}")
    private String geminiKey;

    @Value("${oly.ai.gemini-base-url:https://generativelanguage.googleapis.com/v1beta/openai}")
    private String geminiBaseUrl;

    @Value("${oly.ai.gemini-model:gemini-3.5-flash-lite}")
    private String geminiModel;

    @EventListener(ApplicationReadyEvent.class)
    public void probe() {
        if (!enabled) {
            logger.info("AI startup probe skipped: OLY_AI_ENABLED=false");
            return;
        }

        if (isGemini()) {
            probeCredential("gemini", geminiKey, geminiBaseUrl, geminiModel);
            return;
        }

        String key = trim(openAiKey);
        if (key.startsWith("sk-or-v1-")) {
            probeCredential("openrouter", key, "https://openrouter.ai/api/v1", "openrouter/free");
            return;
        }

        probeCredential("openai", key, openAiBaseUrl, openAiModel);
    }

    private void probeCredential(String providerCandidate, String rawKey, String rawBaseUrl, String model) {
        String key = trim(rawKey);
        if (key.isBlank()) {
            logger.info(
                    "AI startup probe: providerCandidate={}, credentialPresent=false, credentialValid=false, model={}",
                    providerCandidate,
                    model
            );
            return;
        }

        String baseUrl = normalizeBaseUrl(rawBaseUrl);

        try {
            RestClient client = RestClient.builder()
                    .baseUrl(baseUrl)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                    .build();

            client.get()
                    .uri("/models")
                    .retrieve()
                    .body(String.class);

            logger.info(
                    "AI startup probe: providerCandidate={}, credentialPresent=true, credentialValid=true, model={}, baseUrl={}",
                    providerCandidate,
                    model,
                    baseUrl
            );
        } catch (RestClientResponseException e) {
            logger.error(
                    "AI startup probe: providerCandidate={}, credentialPresent=true, credentialValid=false, status={}, model={}, baseUrl={}",
                    providerCandidate,
                    e.getStatusCode(),
                    model,
                    baseUrl
            );
        } catch (Exception e) {
            logger.error(
                    "AI startup probe failed before validation: providerCandidate={}, model={}, baseUrl={}, errorType={}",
                    providerCandidate,
                    model,
                    baseUrl,
                    e.getClass().getSimpleName()
            );
        }
    }

    private boolean isGemini() {
        return provider != null && provider.trim().equalsIgnoreCase("gemini");
    }

    private String normalizeBaseUrl(String value) {
        String normalized = trim(value);
        if (normalized.isBlank()) {
            return "https://api.openai.com/v1";
        }
        return normalized.replaceAll("/+$", "");
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
