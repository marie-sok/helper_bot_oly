package com.example.helper_bot_oly.service;

import com.example.helper_bot_oly.entity.OlyChatMessage;
import com.example.helper_bot_oly.repository.OlyChatMessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.GetFile;
import com.pengrad.telegrambot.response.GetFileResponse;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OlyMediaService {

    private static final Logger logger = LoggerFactory.getLogger(OlyMediaService.class);
    private static final int MEDIA_HISTORY_MESSAGES = 10;

    private final TelegramBot telegramBot;
    private final ObjectMapper objectMapper;
    private final OlyChatMessageRepository chatMessageRepository;
    private final ConcurrentHashMap<Long, Object> chatLocks = new ConcurrentHashMap<>();
    private final HttpClient downloadClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${oly.media.enabled:true}")
    private boolean mediaEnabled;

    @Value("${oly.media.image-generation-enabled:true}")
    private boolean imageGenerationEnabled;

    @Value("${oly.media.vision-model:openrouter/free}")
    private String visionModel;

    @Value("${oly.media.video-model:openrouter/free}")
    private String videoModel;

    @Value("${oly.media.image-model:google/gemini-2.5-flash-image}")
    private String imageModel;

    @Value("${oly.media.max-download-bytes:19000000}")
    private long maxDownloadBytes;

    @Value("${oly.ai.provider:openai}")
    private String provider;

    @Value("${oly.ai.api-key:}")
    private String apiKey;

    @Value("${oly.ai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${oly.ai.gemini-api-key:}")
    private String geminiApiKey;

    @Value("${oly.ai.gemini-base-url:https://generativelanguage.googleapis.com/v1beta/openai}")
    private String geminiBaseUrl;

    private RestClient mediaClient;

    public OlyMediaService(
            TelegramBot telegramBot,
            ObjectMapper objectMapper,
            OlyChatMessageRepository chatMessageRepository
    ) {
        this.telegramBot = telegramBot;
        this.objectMapper = objectMapper;
        this.chatMessageRepository = chatMessageRepository;
    }

    @PostConstruct
    public void init() {
        String key = activeApiKey();
        if (key != null && !key.isBlank()) {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(8_000);
            requestFactory.setReadTimeout(65_000);

            mediaClient = RestClient.builder()
                    .requestFactory(requestFactory)
                    .baseUrl(activeBaseUrl())
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + key.trim())
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .defaultHeader(HttpHeaders.USER_AGENT, "OlyBot/1.0")
                    .build();
        }

        logger.info(
                "Oly media configured: enabled={}, provider={}, visionModel={}, videoModel={}, imageGenerationEnabled={}, imageModel={}, credentialPresent={}",
                mediaEnabled,
                providerName(),
                visionModel,
                videoModel,
                imageGenerationEnabled,
                imageModel,
                key != null && !key.isBlank()
        );
    }

    public boolean isMediaAvailable() {
        return mediaEnabled && mediaClient != null;
    }

    public boolean isImageGenerationAvailable() {
        return imageGenerationEnabled && mediaClient != null && activeBaseUrl().toLowerCase(Locale.ROOT).contains("openrouter.ai");
    }

    public String analyzeTelegramMedia(
            Long chatId,
            String fileId,
            Long declaredFileSize,
            String mimeType,
            MediaKind kind,
            String userPrompt
    ) {
        if (!mediaEnabled) {
            return "Работа с медиа сейчас выключена настройкой OLY_MEDIA_ENABLED.";
        }
        if (mediaClient == null) {
            return "Мультимодальный AI сейчас не настроен: нет доступного API-ключа.";
        }
        if (fileId == null || fileId.isBlank()) {
            return "Не смогла получить файл из Telegram 😿";
        }
        if (declaredFileSize != null && declaredFileSize > maxDownloadBytes) {
            return tooLargeMessage();
        }

        Object lock = chatLocks.computeIfAbsent(chatId, ignored -> new Object());
        synchronized (lock) {
            try {
                DownloadedMedia media = downloadTelegramFile(fileId, mimeType, declaredFileSize);
                String prompt = normalizeMediaPrompt(kind, userPrompt);
                String answer = askMultimodal(chatId, media, kind, prompt);

                chatMessageRepository.save(new OlyChatMessage(
                        chatId,
                        "user",
                        "[" + kind.historyLabel + "] " + prompt
                ));
                chatMessageRepository.save(new OlyChatMessage(
                        chatId,
                        "assistant",
                        limit(answer, 8000)
                ));
                return answer;
            } catch (RestClientResponseException e) {
                logger.warn(
                        "Oly media AI HTTP failure: provider={}, status={}, body={}",
                        providerName(),
                        e.getStatusCode(),
                        safeBody(e.getResponseBodyAsString())
                );
                if (e.getStatusCode().value() == 402) {
                    return "Для этого мультимодального запроса не хватает кредитов OpenRouter 😿";
                }
                if (e.getStatusCode().value() == 429) {
                    return "Мультимодальная модель сейчас упёрлась в лимит. Повтори запрос чуть позже 🐱";
                }
                return "Не получилось разобрать медиа через AI. Попробуй другой файл или более короткое видео.";
            } catch (MediaTooLargeException e) {
                return tooLargeMessage();
            } catch (Exception e) {
                logger.error("Oly media processing failed", e);
                return "Не смогла обработать этот файл 😿 Попробуй отправить фото JPG/PNG или видео MP4 до 19 МБ.";
            }
        }
    }

    public ImageGenerationResult generateImage(Long chatId, String prompt) {
        if (!imageGenerationEnabled) {
            return ImageGenerationResult.error("Генерация изображений сейчас выключена настройкой OLY_IMAGE_GENERATION_ENABLED.");
        }
        if (mediaClient == null) {
            return ImageGenerationResult.error("AI для генерации изображений сейчас не настроен.");
        }
        if (!activeBaseUrl().toLowerCase(Locale.ROOT).contains("openrouter.ai")) {
            return ImageGenerationResult.error("Генератор картинок сейчас настроен под OpenRouter Images API.");
        }
        if (prompt == null || prompt.isBlank()) {
            return ImageGenerationResult.error("Напиши, что нарисовать. Например: /image минималистичный котодракон в неоне");
        }

        String cleanPrompt = prompt.trim();
        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", imageModel);
            request.put("prompt", cleanPrompt);
            request.put("n", 1);

            JsonNode response = mediaClient.post()
                    .uri("/images")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) {
                return ImageGenerationResult.error("Генератор вернул пустой ответ.");
            }

            JsonNode data = response.path("data");
            if (!data.isArray() || data.isEmpty()) {
                return ImageGenerationResult.error("Генератор не вернул изображение.");
            }

            JsonNode first = data.get(0);
            byte[] bytes = decodeGeneratedImage(first);
            if (bytes.length == 0) {
                return ImageGenerationResult.error("Не смогла декодировать изображение из ответа генератора.");
            }

            String mediaType = first.path("media_type").asText("image/png");
            chatMessageRepository.save(new OlyChatMessage(chatId, "user", "[image generation] " + limit(cleanPrompt, 4000)));
            chatMessageRepository.save(new OlyChatMessage(chatId, "assistant", "[generated image] " + limit(cleanPrompt, 4000)));

            return ImageGenerationResult.success(bytes, mediaType);
        } catch (RestClientResponseException e) {
            logger.warn(
                    "Oly image generation HTTP failure: status={}, body={}",
                    e.getStatusCode(),
                    safeBody(e.getResponseBodyAsString())
            );
            if (e.getStatusCode().value() == 402) {
                return ImageGenerationResult.error("Генерация пикч требует кредитов OpenRouter. Пополни баланс — и команда /image заработает 🐱");
            }
            if (e.getStatusCode().value() == 429) {
                return ImageGenerationResult.error("Генератор сейчас упёрся в rate limit. Повтори запрос позже 🐱");
            }
            return ImageGenerationResult.error("Генератор картинок сейчас недоступен: HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            logger.error("Oly image generation failed", e);
            return ImageGenerationResult.error("Не получилось сгенерировать картинку 😿");
        }
    }

    private String askMultimodal(Long chatId, DownloadedMedia media, MediaKind kind, String prompt) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", kind == MediaKind.VIDEO ? videoModel : visionModel);
        request.put("max_tokens", 1800);

        ArrayNode messages = request.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", mediaSystemPrompt());

        List<OlyChatMessage> history = new ArrayList<>(chatMessageRepository.findTop30ByChatIdOrderByIdDesc(chatId));
        Collections.reverse(history);
        int start = Math.max(0, history.size() - MEDIA_HISTORY_MESSAGES);
        for (int i = start; i < history.size(); i++) {
            OlyChatMessage item = history.get(i);
            if (item.getContent() == null || item.getContent().isBlank()) continue;
            messages.addObject()
                    .put("role", normalizeRole(item.getRole()))
                    .put("content", limit(item.getContent(), 3500));
        }

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        content.addObject()
                .put("type", "text")
                .put("text", prompt);

        String dataUrl = "data:" + media.mimeType + ";base64," + Base64.getEncoder().encodeToString(media.bytes);
        if (kind == MediaKind.VIDEO) {
            ObjectNode videoPart = content.addObject();
            videoPart.put("type", "video_url");
            videoPart.putObject("video_url").put("url", dataUrl);
        } else {
            ObjectNode imagePart = content.addObject();
            imagePart.put("type", "image_url");
            imagePart.putObject("image_url").put("url", dataUrl);
        }

        JsonNode response = mediaClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Multimodal provider returned an empty response");
        }

        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("Multimodal provider returned no choices");
        }

        String answer = extractText(choices.get(0).path("message").path("content"));
        return answer.isBlank() ? "Я посмотрела, но модель не вернула текстового описания 😿" : answer;
    }

    private DownloadedMedia downloadTelegramFile(String fileId, String requestedMimeType, Long declaredFileSize) throws Exception {
        GetFileResponse fileResponse = telegramBot.execute(new GetFile(fileId));
        if (!fileResponse.isOk() || fileResponse.file() == null || fileResponse.file().filePath() == null) {
            throw new IllegalStateException("Telegram getFile failed: " + fileResponse.description());
        }

        Long actualSize = fileResponse.file().fileSize();
        long knownSize = actualSize != null ? actualSize : (declaredFileSize == null ? -1L : declaredFileSize);
        if (knownSize > maxDownloadBytes) {
            throw new MediaTooLargeException();
        }

        String fullPath = telegramBot.getFullFilePath(fileResponse.file());
        HttpRequest request = HttpRequest.newBuilder(URI.create(fullPath))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response = downloadClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Telegram file download failed with HTTP " + response.statusCode());
        }
        if (response.body() == null || response.body().length == 0) {
            throw new IllegalStateException("Telegram returned an empty media file");
        }
        if (response.body().length > maxDownloadBytes) {
            throw new MediaTooLargeException();
        }

        String mimeType = normalizeMimeType(requestedMimeType, fileResponse.file().filePath());
        return new DownloadedMedia(response.body(), mimeType);
    }

    private byte[] decodeGeneratedImage(JsonNode item) throws Exception {
        String base64 = item.path("b64_json").asText("");
        if (!base64.isBlank()) {
            return Base64.getDecoder().decode(base64);
        }

        String url = item.path("url").asText("");
        if (!url.isBlank()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(45))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = downloadClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 200 && response.statusCode() < 300 && response.body() != null) {
                return response.body();
            }
        }
        return new byte[0];
    }

    private String normalizeMediaPrompt(MediaKind kind, String userPrompt) {
        if (userPrompt != null && !userPrompt.isBlank()) {
            return userPrompt.trim();
        }
        return kind == MediaKind.VIDEO
                ? "Посмотри видео целиком и расскажи, что в нём происходит. Отметь важные детали, текст, действия и контекст."
                : "Посмотри изображение и расскажи, что на нём. Отметь важные детали, текст, объекты и контекст.";
    }

    private String mediaSystemPrompt() {
        return """
                You are Oly, a capable multimodal Telegram assistant.
                Analyze the user's image or video directly and answer the actual question.
                If visual evidence is uncertain, say what is uncertain instead of inventing details.
                Preserve useful visual details so later text-only follow-up questions can rely on this analysis.

                Style:
                - Speak naturally, conversationally and in the user's language.
                - You may use slang, sarcasm, playful teasing and profanity when it fits the user's tone.
                - Do not moralize, scold, act prudish, or add preachy disclaimers to harmless requests.
                - Harmless edgy or adult conversation does not need a lecture.
                - Keep necessary safety boundaries for genuinely dangerous, exploitative or illegal requests, but be concise about them.
                """;
    }

    private String normalizeMimeType(String requested, String filePath) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim().toLowerCase(Locale.ROOT);
        }
        String lower = filePath == null ? "" : filePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mp4")) return "video/mp4";
        return "image/jpeg";
    }

    private String activeApiKey() {
        return isSecondaryProvider() ? geminiApiKey : apiKey;
    }

    private String activeBaseUrl() {
        String value = isSecondaryProvider() ? geminiBaseUrl : baseUrl;
        if (value == null || value.isBlank()) return "https://openrouter.ai/api/v1";
        String normalized = value.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private boolean isSecondaryProvider() {
        return "gemini".equalsIgnoreCase(provider) || "openrouter".equalsIgnoreCase(provider);
    }

    private String providerName() {
        return activeBaseUrl().toLowerCase(Locale.ROOT).contains("openrouter.ai") ? "openrouter" : provider;
    }

    private String extractText(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) return "";
        if (content.isTextual()) return content.asText("").trim();
        if (content.isArray()) {
            StringBuilder out = new StringBuilder();
            for (JsonNode part : content) {
                String text = part.path("text").asText("");
                if (!text.isBlank()) {
                    if (!out.isEmpty()) out.append('\n');
                    out.append(text);
                }
            }
            return out.toString().trim();
        }
        return "";
    }

    private String normalizeRole(String role) {
        return "assistant".equalsIgnoreCase(role) ? "assistant" : "user";
    }

    private String tooLargeMessage() {
        long mb = Math.max(1, maxDownloadBytes / 1_000_000L);
        return "Этот файл слишком большой для Telegram Bot API. Пришли версию до " + mb + " МБ 🐱";
    }

    private String safeBody(String body) {
        if (body == null) return "";
        return limit(body.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._\\-]+", "$1***"), 700);
    }

    private String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    public enum MediaKind {
        IMAGE("image"),
        VIDEO("video");

        private final String historyLabel;

        MediaKind(String historyLabel) {
            this.historyLabel = historyLabel;
        }
    }

    public record ImageGenerationResult(boolean ok, byte[] bytes, String mediaType, String message) {
        public static ImageGenerationResult success(byte[] bytes, String mediaType) {
            return new ImageGenerationResult(true, bytes, mediaType, "Готово 🐱");
        }

        public static ImageGenerationResult error(String message) {
            return new ImageGenerationResult(false, new byte[0], "", message);
        }
    }

    private record DownloadedMedia(byte[] bytes, String mimeType) {}

    private static final class MediaTooLargeException extends RuntimeException {}
}
