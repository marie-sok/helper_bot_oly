package com.example.helper_bot_oly.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenRouterCompatibilityBridge {

    private static final Logger logger = LoggerFactory.getLogger(OpenRouterCompatibilityBridge.class);

    @Bean
    public static BeanFactoryPostProcessor openRouterCompatibilityBridgePostProcessor() {
        return beanFactory -> {
            String baseUrl = firstNonBlank(
                    System.getProperty("oly.ai.base-url"),
                    System.getenv("OPENAI_BASE_URL")
            );
            String apiKey = firstNonBlank(
                    System.getProperty("oly.ai.api-key"),
                    System.getenv("OPENAI_API_KEY")
            );

            if (!hasText(baseUrl)
                    || !baseUrl.toLowerCase().contains("openrouter.ai")
                    || !hasText(apiKey)) {
                return;
            }

            String model = firstNonBlank(
                    System.getProperty("oly.ai.model"),
                    System.getenv("OPENAI_MODEL"),
                    "openrouter/free"
            );

            String normalizedBaseUrl = baseUrl.trim().replaceAll("/+$", "");

            // Oly already has a robust OpenAI-compatible Chat Completions tool loop
            // behind its secondary provider path. Reuse it for OpenRouter instead of
            // the stateful Responses API path, because OpenRouter requires store=false.
            System.setProperty("oly.ai.provider", "gemini");
            System.setProperty("oly.ai.gemini-api-key", apiKey.trim());
            System.setProperty("GEMINI_API_KEY", apiKey.trim());
            System.setProperty("oly.ai.gemini-base-url", normalizedBaseUrl);
            System.setProperty("oly.ai.gemini-model", model.trim());
            System.setProperty("oly.ai.web-search-enabled", "false");

            logger.info(
                    "OpenRouter compatibility mode enabled: protocol=chat/completions, model={}, baseUrl={}",
                    model.trim(),
                    normalizedBaseUrl
            );
        };
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
