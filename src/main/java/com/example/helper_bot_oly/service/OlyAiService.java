package com.example.helper_bot_oly.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class OlyAiService {

    private static final Logger logger = LoggerFactory.getLogger(OlyAiService.class);

    private static final String INSTRUCTIONS = """
            Ты Оли — персональный Telegram AI-агент Марии.

            Твой характер: умная, быстрая, тёплая, немного ироничная, без канцелярита.
            Не изображай человека и не утверждай, что являешься Марией.
            Отвечай на языке пользователя, если он явно не попросил другой язык.
            По умолчанию отвечай компактно, но полно по смыслу.
            Помогай думать, планировать, разбирать учёбу и работу, писать и редактировать тексты,
            генерировать идеи и вести обычный разговор.

            Напоминания создаёт отдельная функция бота. Если пользователь хочет напоминание,
            объясни формат: dd.MM.yyyy HH:mm Текст напоминания.
            Не выдумывай, что напоминание создано, если это не было подтверждено ботом.
            """;

    private final Map<Long, String> previousResponseIds = new ConcurrentHashMap<>();

    @Value("${oly.ai.enabled:true}")
    private boolean enabled;

    @Value("${oly.ai.model:gpt-5.6-luna}")
    private String model;

    private volatile OpenAIClient client;

    public String reply(Long chatId, String userText) {
        if (!enabled) {
            return "AI-режим Оли сейчас выключен.";
        }

        if (chatId == null || userText == null || userText.isBlank()) {
            return "Напиши мне что-нибудь — я на связи 🐱";
        }

        if (System.getenv("OPENAI_API_KEY") == null || System.getenv("OPENAI_API_KEY").isBlank()) {
            logger.warn("OPENAI_API_KEY is not configured");
            return "AI-режим пока не настроен: на сервере нет OPENAI_API_KEY.";
        }

        try {
            ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                    .model(ChatModel.of(model))
                    .instructions(INSTRUCTIONS)
                    .input(userText.trim())
                    .store(true);

            String previousResponseId = previousResponseIds.get(chatId);
            if (previousResponseId != null && !previousResponseId.isBlank()) {
                builder.previousResponseId(previousResponseId);
            }

            Response response = client().responses().create(builder.build());

            previousResponseIds.put(chatId, response.id());

            String text = response.output().stream()
                    .flatMap(item -> item.message().stream())
                    .flatMap(message -> message.content().stream())
                    .flatMap(content -> content.outputText().stream())
                    .map(outputText -> outputText.text())
                    .filter(value -> value != null && !value.isBlank())
                    .collect(Collectors.joining("\n"))
                    .trim();

            if (text.isEmpty()) {
                logger.warn("OpenAI response contained no output text for chat {}", chatId);
                return "Я получила пустой ответ. Попробуй переформулировать сообщение.";
            }

            return text;
        } catch (Exception exception) {
            logger.error("AI request failed for chat {}", chatId, exception);
            return "У меня сейчас не получилось достучаться до AI. Попробуй ещё раз чуть позже.";
        }
    }

    public void resetConversation(Long chatId) {
        if (chatId != null) {
            previousResponseIds.remove(chatId);
        }
    }

    private OpenAIClient client() {
        OpenAIClient current = client;
        if (current != null) {
            return current;
        }

        synchronized (this) {
            if (client == null) {
                client = OpenAIOkHttpClient.fromEnv();
            }
            return client;
        }
    }
}
