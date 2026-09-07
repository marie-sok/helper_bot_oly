package com.example.helper_bot_oly.config;

import com.example.helper_bot_oly.service.OlyMaxAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Makes the external AI dependency fail fast instead of blocking Telegram workers forever.
 * OlyMaxAgentService historically built RestClient with library defaults, which can wait
 * indefinitely for a slow/free upstream route. This post-processor replaces only its
 * transport after normal property binding/PostConstruct has completed.
 */
@Component
public class OlyAgentHttpTimeoutConfig implements BeanPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(OlyAgentHttpTimeoutConfig.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof OlyMaxAgentService)) {
            return bean;
        }

        try {
            String provider = stringField(bean, "provider");
            boolean chatCompletions = provider != null && (
                    provider.equalsIgnoreCase("gemini") ||
                    provider.equalsIgnoreCase("openrouter")
            );
            if (!chatCompletions) {
                return bean;
            }

            String key;
            String baseUrl;
            if (provider.equalsIgnoreCase("gemini")) {
                key = stringField(bean, "geminiApiKey");
                baseUrl = stringField(bean, "geminiBaseUrl");
            } else {
                key = stringField(bean, "apiKey");
                baseUrl = stringField(bean, "baseUrl");
            }

            if (key == null || key.isBlank() || baseUrl == null || baseUrl.isBlank()) {
                return bean;
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

            Field agentClient = findField(bean.getClass(), "agentClient");
            agentClient.setAccessible(true);
            agentClient.set(bean, timedClient);

            logger.info(
                    "Oly AI network watchdog enabled: connectTimeout={}s, readTimeout={}s",
                    CONNECT_TIMEOUT.toSeconds(),
                    READ_TIMEOUT.toSeconds()
            );
        } catch (Exception e) {
            logger.error("Unable to install Oly AI network watchdog", e);
        }
        return bean;
    }

    private String stringField(Object bean, String name) throws Exception {
        Field field = findField(bean.getClass(), name);
        field.setAccessible(true);
        Object value = field.get(bean);
        return value == null ? "" : value.toString();
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
