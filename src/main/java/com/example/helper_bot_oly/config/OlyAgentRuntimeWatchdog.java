package com.example.helper_bot_oly.config;

import com.example.helper_bot_oly.service.OlyMaxAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class OlyAgentRuntimeWatchdog implements ApplicationListener<ApplicationReadyEvent>, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(OlyAgentRuntimeWatchdog.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(18);

    private final OlyMaxAgentService agent;
    private final Environment environment;

    public OlyAgentRuntimeWatchdog(OlyMaxAgentService agent, Environment environment) {
        this.agent = agent;
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        // Run before TelegramWebhookService's default-order ApplicationReady listener.
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            String provider = environment.getProperty("oly.ai.provider", "openai");
            String key;
            String baseUrl;

            if ("gemini".equalsIgnoreCase(provider)) {
                key = environment.getProperty("oly.ai.gemini-api-key", "");
                baseUrl = environment.getProperty(
                        "oly.ai.gemini-base-url",
                        "https://generativelanguage.googleapis.com/v1beta/openai"
                );
            } else {
                key = environment.getProperty("oly.ai.api-key", "");
                baseUrl = environment.getProperty("oly.ai.base-url", "https://api.openai.com/v1");
            }

            if (key == null || key.isBlank()) {
                logger.warn("Oly runtime watchdog skipped: AI credential is empty");
                return;
            }

            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(READ_TIMEOUT);

            RestClient timedClient = RestClient.builder()
                    .requestFactory(requestFactory)
                    .baseUrl(baseUrl.trim().replaceAll("/+$", ""))
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + key.trim())
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .defaultHeader(HttpHeaders.USER_AGENT, "OlyBot/1.0")
                    .build();

            Field field = findField(agent.getClass(), "agentClient");
            field.setAccessible(true);
            field.set(agent, timedClient);

            logger.info(
                    "Oly runtime AI watchdog installed: connectTimeout={}s, readTimeout={}s, provider={}, baseUrl={}",
                    CONNECT_TIMEOUT.toSeconds(),
                    READ_TIMEOUT.toSeconds(),
                    provider,
                    baseUrl.trim().replaceAll("/+$", "")
            );
        } catch (Exception e) {
            logger.error("Oly runtime AI watchdog installation failed", e);
        }
    }

    private Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
