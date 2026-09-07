package com.example.helper_bot_oly.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Configuration(proxyBeanMethods = false)
public class GeminiEnvironmentBridge {

    private static final Logger logger = LoggerFactory.getLogger(GeminiEnvironmentBridge.class);

    private static final List<String> PREFERRED_NAMES = List.of(
            "GEMINI_API_KEY",
            "GOOGLE_GEMINI_API_KEY",
            "GOOGLE_API_KEY",
            "GEMINI_KEY",
            "GEMINI",
            "GOOGLE_AI_API_KEY",
            "GOOGLE_GENAI_API_KEY",
            "AI_STUDIO_API_KEY"
    );

    private static final Pattern GOOGLE_API_KEY_PATTERN =
            Pattern.compile("^AIza[0-9A-Za-z_-]{20,}$");

    @Bean
    public static BeanFactoryPostProcessor geminiEnvironmentBridgePostProcessor() {
        return beanFactory -> {
            if (hasText(System.getProperty("GEMINI_API_KEY"))) {
                return;
            }

            Map<String, String> env = System.getenv();
            Map.Entry<String, String> match = findGeminiCredential(env);
            if (match == null) {
                logger.info("Gemini environment bridge: no Gemini credential candidate found");
                return;
            }

            String value = match.getValue().trim();
            System.setProperty("GEMINI_API_KEY", value);
            System.setProperty("oly.ai.gemini-api-key", value);
            logger.info("Gemini environment bridge: credential discovered from env key {}", match.getKey());
        };
    }

    private static Map.Entry<String, String> findGeminiCredential(Map<String, String> env) {
        for (String name : PREFERRED_NAMES) {
            String value = env.get(name);
            if (hasText(value)) {
                return Map.entry(name, value);
            }
        }

        for (Map.Entry<String, String> entry : env.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().toUpperCase();
            String value = entry.getValue();
            if (hasText(value)
                    && name.contains("GEMINI")
                    && (name.contains("KEY") || name.contains("TOKEN") || name.contains("SECRET"))) {
                return entry;
            }
        }

        for (Map.Entry<String, String> entry : env.entrySet()) {
            String value = entry.getValue();
            if (hasText(value) && GOOGLE_API_KEY_PATTERN.matcher(value.trim()).matches()) {
                return entry;
            }
        }

        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
