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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TelegramBotUpdatesListener implements UpdatesListener {

    private static final Logger logger = LoggerFactory.getLogger(TelegramBotUpdatesListener.class);

    private static final Pattern REMINDER_PATTERN = Pattern.compile(
            "(\\d{2}\\.\\d{2}\\.\\d{4}\\s\\d{2}:\\d{2})(\\s+)(.+)"
    );

    private static final DateTimeFormatter REMINDER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private static final int TELEGRAM_SAFE_MESSAGE_LENGTH = 3900;

    private final TelegramBot telegramBot;
    private final HelperTaskRepository helperTaskRepository;
    private final OlyAiService olyAiService;

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
        logger.info("helper_bot_oly listener started");
    }

    @Override
    public int process(List<Update> updates) {
        for (Update update : updates) {
            try {
                processUpdate(update);
            } catch (Exception exception) {
                logger.error("Error processing Telegram update", exception);
            }
        }

        return UpdatesListener.CONFIRMED_UPDATES_ALL;
    }

    private void processUpdate(Update update) {
        if (update == null || update.message() == null || update.message().chat() == null) {
            return;
        }

        String text = update.message().text();
        if (text == null || text.isBlank()) {
            return;
        }

        Long chatId = update.message().chat().id();
        String cleanText = text.trim();
        String normalized = cleanText.toLowerCase(Locale.ROOT);
        String command = normalized.split("\\s+", 2)[0];

        logger.info("Telegram message received from chat {}", chatId);

        if (command.startsWith("/start")) {
            sendWelcomeMessage(chatId);
            return;
        }

        if (command.startsWith("/help")) {
            sendHelp(chatId);
            return;
        }

        if (command.startsWith("/new") || command.startsWith("/reset")) {
            olyAiService.resetConversation(chatId);
            sendMessage(chatId, "Новый диалог начат. Контекст Оли очищен 🐱");
            return;
        }

        if (command.startsWith("/joke")
                || normalized.contains("оли,пошути")
                || normalized.contains("оли, пошути")
                || normalized.contains("оли,развесели")
                || normalized.contains("оли, развесели")) {
            sendJoke(chatId);
            return;
        }

        if (normalized.contains("кто балуется") || normalized.contains("кто вредничает")) {
            sendWhoIsMisbehaving(chatId);
            return;
        }

        if (REMINDER_PATTERN.matcher(cleanText).matches()) {
            processReminder(chatId, cleanText);
            return;
        }

        String aiResponse = olyAiService.reply(chatId, cleanText);
        sendLongMessage(chatId, aiResponse);
    }

    private void sendWelcomeMessage(Long chatId) {
        sendMessage(chatId, """
                Привет. Я Оли 🐱
                Персональный AI-агент в Telegram: могу поговорить, помочь подумать,
                разобрать задачу, текст, учёбу или план — и ещё умею ставить напоминания.

                Просто напиши мне обычным сообщением.

                Напоминание:
                dd.MM.yyyy HH:mm Текст

                Пример:
                05.09.2026 19:30 Проверить backend

                /new — начать новый AI-диалог
                /joke — старая добрая глупость
                /help — краткая справка
                """);
    }

    private void sendHelp(Long chatId) {
        sendMessage(chatId, """
                Оли понимает обычный текст — специальных команд для AI не нужно.

                /new или /reset — очистить контекст разговора
                /joke — шутка
                /help — эта справка

                Напоминание создаётся сообщением формата:
                dd.MM.yyyy HH:mm Текст напоминания
                """);
    }

    private void sendJoke(Long chatId) {
        sendMessage(chatId, "Купил мужик шляпу — а она ему как раз!");
    }

    private void sendWhoIsMisbehaving(Long chatId) {
        try {
            File imageFile = new File("src/main/resources/static/who_misbehaving.jpg");
            telegramBot.execute(new SendPhoto(chatId, imageFile));
        } catch (Exception exception) {
            logger.error("Error sending misbehaving photo", exception);
            sendMessage(chatId, "Не смогла найти картинку 😔");
        }
    }

    private void processReminder(Long chatId, String messageText) {
        Matcher matcher = REMINDER_PATTERN.matcher(messageText);

        if (!matcher.matches()) {
            return;
        }

        try {
            String dateTimeString = matcher.group(1);
            String reminderText = matcher.group(3).trim();
            LocalDateTime reminderDateTime = LocalDateTime.parse(
                    dateTimeString,
                    REMINDER_DATE_FORMAT
            );

            if (reminderDateTime.isBefore(LocalDateTime.now())) {
                sendMessage(chatId, "Не могу поставить напоминание в прошлое.");
                return;
            }

            HelperTask task = new HelperTask(chatId, reminderText, reminderDateTime);
            helperTaskRepository.save(task);

            sendMessage(
                    chatId,
                    "Готово. Напомню %s:\n%s".formatted(dateTimeString, reminderText)
            );
        } catch (Exception exception) {
            logger.error("Error creating reminder from message: {}", messageText, exception);
            sendMessage(chatId, "Не разобрала дату. Формат: dd.MM.yyyy HH:mm Текст");
        }
    }

    @Scheduled(cron = "0 * * * * *")
    public void sendScheduledNotifications() {
        try {
            LocalDateTime currentDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
            List<HelperTask> tasks = helperTaskRepository.findAllByNotificationDateTime(currentDateTime);

            for (HelperTask task : tasks) {
                String notificationMessage = "Напоминаю 🐱\n" + task.getMessageText();
                if (executeMessage(new SendMessage(task.getChatId(), notificationMessage))) {
                    logger.info("Reminder sent to chat {}", task.getChatId());
                }
            }
        } catch (Exception exception) {
            logger.error("Error sending scheduled reminders", exception);
        }
    }

    private void sendLongMessage(Long chatId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        String remaining = text.trim();

        while (remaining.length() > TELEGRAM_SAFE_MESSAGE_LENGTH) {
            int splitAt = findSplitPoint(remaining, TELEGRAM_SAFE_MESSAGE_LENGTH);
            sendMessage(chatId, remaining.substring(0, splitAt).trim());
            remaining = remaining.substring(splitAt).trim();
        }

        if (!remaining.isEmpty()) {
            sendMessage(chatId, remaining);
        }
    }

    private int findSplitPoint(String text, int preferredLimit) {
        int newline = text.lastIndexOf('\n', preferredLimit);
        if (newline > preferredLimit / 2) {
            return newline + 1;
        }

        int space = text.lastIndexOf(' ', preferredLimit);
        if (space > preferredLimit / 2) {
            return space + 1;
        }

        return preferredLimit;
    }

    private void sendMessage(Long chatId, String text) {
        executeMessage(new SendMessage(chatId, text));
    }

    private boolean executeMessage(SendMessage message) {
        try {
            SendResponse response = telegramBot.execute(message);

            if (!response.isOk()) {
                logger.warn("Telegram API rejected message: {}", response.description());
            }

            return response.isOk();
        } catch (Exception exception) {
            logger.error("Error sending Telegram message", exception);
            return false;
        }
    }
}
