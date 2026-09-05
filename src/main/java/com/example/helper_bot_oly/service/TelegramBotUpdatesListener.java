package com.example.helper_bot_oly.service;

import com.example.helper_bot_oly.entity.HelperTask;
import com.example.helper_bot_oly.repository.HelperTaskRepository;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.request.SendPhoto;
import com.pengrad.telegrambot.response.SendResponse;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TelegramBotUpdatesListener implements UpdatesListener {

    private static final Logger logger = LoggerFactory.getLogger(TelegramBotUpdatesListener.class);
    private static final int TELEGRAM_SAFE_TEXT_LIMIT = 3900;

    private final Pattern legacyReminderPattern = Pattern.compile(
            "(\\d{2}\\.\\d{2}\\.\\d{4}\\s\\d{2}:\\d{2})(\\s+)(.+)"
    );
    private final DateTimeFormatter legacyReminderFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final TelegramBot telegramBot;
    private final HelperTaskRepository helperTaskRepository;
    private final OlyAiService olyAiService;

    @Value("${oly.timezone:Europe/Amsterdam}")
    private String timeZone;

    public TelegramBotUpdatesListener(
            TelegramBot telegramBot,
            HelperTaskRepository helperTaskRepository,
            OlyAiService olyAiService
    ) {
        this.telegramBot = telegramBot;
        this.helperTaskRepository = helperTaskRepository;
        this.olyAiService = olyAiService;
    }

    @PostConstruct
    public void init() {
        telegramBot.setUpdatesListener(this);
        logger.info("Oly Telegram listener started. AI enabled={}, model={}",
                olyAiService.isAvailable(),
                olyAiService.getModel());
    }

    @Override
    public int process(List<Update> updates) {
        for (Update update : updates) {
            try {
                processUpdate(update);
            } catch (Exception e) {
                logger.error("Error processing Telegram update", e);
            }
        }
        return UpdatesListener.CONFIRMED_UPDATES_ALL;
    }

    private void processUpdate(Update update) {
        if (update.message() == null || update.message().chat() == null) {
            return;
        }

        Long chatId = update.message().chat().id();
        String messageText = update.message().text();

        if (messageText == null || messageText.isBlank()) {
            sendMessage(chatId, "Пока я работаю с текстовыми сообщениями. Напиши мне текстом 🐱");
            return;
        }

        String text = messageText.trim();
        String command = extractCommand(text);

        switch (command) {
            case "/start", "/help" -> sendWelcomeMessage(chatId);
            case "/reset" -> resetAiConversation(chatId);
            case "/ai" -> sendAiStatus(chatId);
            case "/joke" -> sendJoke(chatId);
            default -> processUserText(chatId, text);
        }
    }

    private void processUserText(Long chatId, String text) {
        String lower = text.toLowerCase(Locale.ROOT);

        if (lower.contains("кто балуется") || lower.contains("кто вредничает")) {
            sendWhoIsMisbehaving(chatId);
            return;
        }

        if (legacyReminderPattern.matcher(text).matches()) {
            processLegacyReminder(chatId, text);
            return;
        }

        String response = olyAiService.reply(chatId, text);
        sendLongMessage(chatId, response);
    }

    private void resetAiConversation(Long chatId) {
        olyAiService.resetConversation(chatId);
        sendMessage(chatId, "Контекст нашего AI-диалога очищен. Начинаем с чистого листа 🐱");
    }

    private void sendAiStatus(Long chatId) {
        String status = olyAiService.isAvailable() ? "online" : "not configured";
        sendMessage(chatId, "Oly AI: " + status + "\nModel: " + olyAiService.getModel());
    }

    private void sendWelcomeMessage(Long chatId) {
        String welcomeText = """
                Oly 🐱 — твой AI-агент в Telegram.

                Просто пиши обычным языком. Я могу:
                • поддерживать полноценный диалог и помнить контекст;
                • искать свежую информацию в интернете;
                • помогать с учебой, текстами, идеями и планированием;
                • создавать напоминания из обычной фразы;
                • показывать и удалять твои напоминания.

                Примеры:
                «Напомни завтра в 18:00 позвонить маме»
                «Какие у меня напоминания?»
                «Удали напоминание про звонок»
                «Что сегодня нового в AI?»

                Команды:
                /ai — статус AI
                /reset — забыть текущий контекст диалога
                /joke — рассказать шутку
                /help — эта справка

                Старый формат напоминаний тоже работает:
                dd.MM.yyyy HH:mm текст
                """;
        sendLongMessage(chatId, welcomeText);
    }

    private void sendJoke(Long chatId) {
        if (olyAiService.isAvailable()) {
            sendLongMessage(chatId, olyAiService.reply(
                    chatId,
                    "Расскажи одну короткую смешную шутку. Без длинного вступления."
            ));
        } else {
            sendMessage(chatId, "Купил мужик шляпу — а она ему как раз! 😼");
        }
    }

    private void sendWhoIsMisbehaving(Long chatId) {
        try {
            File imageFile = new File("src/main/resources/static/who_misbehaving.jpg");
            telegramBot.execute(new SendPhoto(chatId, imageFile));
        } catch (Exception e) {
            logger.error("Error sending Oly picture", e);
            sendMessage(chatId, "Не смогла найти картинку 😿");
        }
    }

    private void processLegacyReminder(Long chatId, String messageText) {
        Matcher matcher = legacyReminderPattern.matcher(messageText);
        if (!matcher.matches()) {
            return;
        }

        try {
            String dateTimeString = matcher.group(1);
            String notificationText = matcher.group(3).trim();
            LocalDateTime notificationDateTime = LocalDateTime.parse(dateTimeString, legacyReminderFormatter);

            if (!notificationDateTime.isAfter(now())) {
                sendMessage(chatId, "Нельзя поставить напоминание в прошлом.");
                return;
            }

            HelperTask task = helperTaskRepository.save(
                    new HelperTask(chatId, notificationText, notificationDateTime)
            );

            sendMessage(
                    chatId,
                    "Готово 🐱\nНапоминание #" + task.getId() + "\n" +
                            dateTimeString + " — " + notificationText
            );
        } catch (Exception e) {
            logger.error("Error parsing legacy reminder", e);
            sendMessage(chatId, "Не смогла разобрать дату. Формат: dd.MM.yyyy HH:mm текст");
        }
    }

    @Scheduled(cron = "0 * * * * *")
    public void sendScheduledNotifications() {
        LocalDateTime currentDateTime = now().truncatedTo(ChronoUnit.MINUTES);

        try {
            List<HelperTask> tasks = helperTaskRepository.findAllByNotificationDateTime(currentDateTime);

            for (HelperTask task : tasks) {
                String notificationMessage = "⏰ Oly напоминает:\n" + task.getMessageText();
                if (executeMessage(new SendMessage(task.getChatId(), notificationMessage))) {
                    helperTaskRepository.delete(task);
                    logger.info("Reminder {} sent to chat {}", task.getId(), task.getChatId());
                }
            }
        } catch (Exception e) {
            logger.error("Error sending scheduled reminders", e);
        }
    }

    private String extractCommand(String text) {
        if (!text.startsWith("/")) {
            return "";
        }

        String firstToken = text.split("\\s+", 2)[0];
        int botSuffix = firstToken.indexOf('@');
        if (botSuffix >= 0) {
            firstToken = firstToken.substring(0, botSuffix);
        }
        return firstToken.toLowerCase(Locale.ROOT);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(zoneId());
    }

    private ZoneId zoneId() {
        try {
            return ZoneId.of(timeZone);
        } catch (Exception e) {
            logger.warn("Invalid timezone '{}', falling back to Europe/Amsterdam", timeZone);
            return ZoneId.of("Europe/Amsterdam");
        }
    }

    private void sendLongMessage(Long chatId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + TELEGRAM_SAFE_TEXT_LIMIT, text.length());

            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end);
                int space = text.lastIndexOf(' ', end);
                int preferredBreak = Math.max(newline, space);
                if (preferredBreak > start + 1000) {
                    end = preferredBreak;
                }
            }

            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                sendMessage(chatId, chunk);
            }
            start = end;
        }
    }

    private void sendMessage(Long chatId, String text) {
        executeMessage(new SendMessage(chatId, text));
    }

    private boolean executeMessage(SendMessage message) {
        try {
            SendResponse response = telegramBot.execute(message);
            if (!response.isOk()) {
                logger.warn("Telegram send failed: {}", response.description());
            }
            return response.isOk();
        } catch (Exception e) {
            logger.error("Error sending Telegram message", e);
            return false;
        }
    }
}
