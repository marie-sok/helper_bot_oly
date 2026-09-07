package com.example.helper_bot_oly.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Configuration(proxyBeanMethods = false)
public class GeminiEnvironmentBridge {

    private static final Logger logger = LoggerFactory.getLogger(GeminiEnvironmentBridge.class);
    private static final Path RENDER_SECRETS_DIR = Path.of("/etc/secrets");
    private static final long MAX_SECRET_FILE_BYTES = 64 * 1024;

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
            Pattern.compile("AIza[0-9A-Za-z_-]{20,}");

    @Bean
    public static BeanFactoryPostProcessor geminiEnvironmentBridgePostProcessor() {
        return beanFactory -> {
            if (hasText(System.getProperty("GEMINI_API_KEY"))) {
                return;
            }

            Map.Entry<String, String> envMatch = findGeminiCredential(System.getenv());
            if (envMatch != null) {
                installCredential(envMatch.getValue(), "env key " + envMatch.getKey());
                return;
            }

            SecretFileCredential fileMatch = findSecretFileCredential();
            if (fileMatch != null) {
                installCredential(fileMatch.value(), "secret file " + fileMatch.path().getFileName());
                return;
            }

            logger.info("Gemini environment bridge: no Gemini credential candidate found in env or /etc/secrets");
        };
    }

    private static void installCredential(String value, String source) {
        String key = extractGoogleApiKey(value);
        if (!hasText(key)) {
            key = value == null ? "" : value.trim();
        }
        if (!hasText(key)) {
            return;
        }

        System.setProperty("GEMINI_API_KEY", key);
        System.setProperty("oly.ai.gemini-api-key", key);
        logger.info("Gemini environment bridge: credential discovered from {}", source);
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
            if (hasText(extractGoogleApiKey(entry.getValue()))) {
                return entry;
            }
        }

        return null;
    }

    private static SecretFileCredential findSecretFileCredential() {
        if (!Files.isDirectory(RENDER_SECRETS_DIR)) {
            return null;
        }

        try (Stream<Path> files = Files.list(RENDER_SECRETS_DIR)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                try {
                    long size = Files.size(path);
                    if (size <= 0 || size > MAX_SECRET_FILE_BYTES) {
                        continue;
                    }

                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    String key = extractGoogleApiKey(content);
                    if (hasText(key)) {
                        return new SecretFileCredential(path, key);
                    }
                } catch (Exception e) {
                    logger.debug("Gemini environment bridge: skipped unreadable secret file {}", path.getFileName());
                }
            }
        } catch (Exception e) {
            logger.debug("Gemini environment bridge: could not inspect /etc/secrets");
        }

        return null;
    }

    private static String extractGoogleApiKey(String value) {
        if (!hasText(value)) {
            return "";
        }
        Matcher matcher = GOOGLE_API_KEY_PATTERN.matcher(value);
        return matcher.find() ? matcher.group() : "";
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record SecretFileCredential(Path path, String value) {
    }
}
