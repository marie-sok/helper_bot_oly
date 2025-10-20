package com.example.helper_bot_oly.service;

import com.example.helper_bot_oly.entity.HelperTask;
import com.example.helper_bot_oly.repository.HelperTaskRepository;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.request.SendPhoto;
import com.pengrad.telegrambot.response.SendResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.File;

@Service
public class TelegramBotUpdatesListener implements UpdatesListener {

    private final Logger logger = LoggerFactory.getLogger(TelegramBotUpdatesListener.class);
    private final Pattern pattern = Pattern.compile("(\\d{2}\\.\\d{2}\\.\\d{4}\\s\\d{2}:\\d{2})(\\s+)(.+)");
    private final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private final ConcurrentHashMap<Long, Boolean> nightModeNotifiedChats = new ConcurrentHashMap<>();

    private final LocalTime NIGHT_MODE_START = LocalTime.of(22, 0);
    private final LocalTime NIGHT_MODE_END = LocalTime.of(8, 0);

    @Autowired
    private TelegramBot telegramBot;

    @Autowired
    private HelperTaskRepository helperTaskRepository;

    @PostConstruct
    public void init() {
        telegramBot.setUpdatesListener(this);
    }

    @Override
    public int process(List<Update> updates) {
        try {
            if (isNightMode()) {
                return processNightMode(updates);
            } else {
                nightModeNotifiedChats.clear();
                return processNormalMode(updates);
            }
        } catch (Exception e) {
            logger.error("Error processing updates", e);
        }
        return UpdatesListener.CONFIRMED_UPDATES_ALL;
    }

    private boolean isNightMode() {
        LocalTime now = LocalTime.now();
        return now.isAfter(NIGHT_MODE_START) || now.isBefore(NIGHT_MODE_END);
    }

    private int processNightMode(List<Update> updates) {
        updates.forEach(update -> {
            if (update.message() != null && update.message().text() != null && update.message().chat() != null) {
                Long chatId = update.message().chat().id();
                String messageText = update.message().text();

                if (!nightModeNotifiedChats.containsKey(chatId)) {
                    sendNightModeMessage(chatId);
                    nightModeNotifiedChats.put(chatId, true);
                }

                logger.info("Night mode: Message from chat {} ignored: {}", chatId, messageText);
            }
        });
        return UpdatesListener.CONFIRMED_UPDATES_ALL;
    }

    private int processNormalMode(List<Update> updates) {
        updates.forEach(update -> {
            logger.info("Processing update: {}", update);
            if (update.message() != null && update.message().text() != null && update.message().chat() != null) {
                String messageText = update.message().text();
                Long chatId = update.message().chat().id();

                if ("/start".equals(messageText)) {
                    sendWelcomeMessage(chatId);
                } else if ("/joke".equals(messageText) ||
                        messageText.toLowerCase().contains("оли,пошути") ||
                        messageText.toLowerCase().contains("оли,развесели") ||
                        messageText.toLowerCase().contains("шутка") ||
                        messageText.toLowerCase().contains("мем")) {
                    sendJoke(chatId);
                } else if (messageText.toLowerCase().contains("кто балуется") ||
                        messageText.toLowerCase().contains("кто вредничает")) {
                    sendWhoIsMisbehaving(chatId);
                } else {
                    processNotificationMessage(chatId, messageText);
                }
            }
        });
        return UpdatesListener.CONFIRMED_UPDATES_ALL;
    }

    private void sendNightModeMessage(Long chatId) {
        String nightMessage = "Приветики, это бот Оли, а значит Маша спит и ответит позже🐱";
        executeMessage(new SendMessage(chatId, nightMessage));
    }

    private void sendWelcomeMessage(Long chatId) {
        String welcomeText = """
                Hello, Marie 🐱
                can I help you today ?
                 we can be to plan a your day 
                 or just small talk about your study
                 He-he

                      Available commands:
                      /start - Show this message
                      /joke - Tell a funny story

                      To set a reminder, send:
                      dd.MM.yyyy HH:mm Your reminder text

                      Example:
                      01.01.2025 20:00 Do homework
                """;
        executeMessage(new SendMessage(chatId, welcomeText));
    }

    private void sendJoke(Long chatId) {
        String joke = "Купил мужик шляпу - а она ему как раз!";
        executeMessage(new SendMessage(chatId, joke));
    }

    private void sendWhoIsMisbehaving(Long chatId) {
        try {
            File imageFile = new File("src/main/resources/static/who_misbehaving.jpg");
            SendPhoto photoRequest = new SendPhoto(chatId, imageFile);
            telegramBot.execute(photoRequest);
        } catch (Exception e) {
            logger.error("Error sending photo", e);
            sendMessage(chatId, "Cant search a picture 😔");
        }
    }

    private void processNotificationMessage(Long chatId, String messageText) {
        Matcher matcher = pattern.matcher(messageText);

        if (matcher.matches()) {
            try {
                String dateTimeString = matcher.group(1);
                String notificationText = matcher.group(3);
                LocalDateTime notificationDateTime = LocalDateTime.parse(dateTimeString, dateTimeFormatter);

                if (notificationDateTime.isBefore(LocalDateTime.now())) {
                    sendMessage(chatId, "Unable to set a reminder for a past date");
                    return;
                }

                HelperTask task = new HelperTask(chatId, notificationText, notificationDateTime);
                helperTaskRepository.save(task);

                String response = String.format("The reminder will work %s:\n%s", dateTimeString, notificationText);
                sendMessage(chatId, response);

            } catch (Exception e) {
                logger.error("Error parsing date from message: {}", messageText, e);
                sendMessage(chatId, "Check the input format");
            }
        } else {
            sendMessage(chatId, """
                    Invalid message format!
                    
                    Use: dd.MM.yyyy HH:mm Reminder text
                    Example: 01.01.2025 20:00 Do homework
                    
                    Or try: /joke - for a funny story
                    """);
        }
    }

    @Scheduled(cron = "0 * * * * *")
    public void sendScheduledNotifications() {
        try {
            LocalDateTime currentDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
            logger.info("Checking notifications for: {}", currentDateTime);

            List<HelperTask> tasks = helperTaskRepository.findAllByNotificationDateTime(currentDateTime);

            for (HelperTask task : tasks) {
                String notificationMessage = String.format("Remind:\n%s", task.getMessageText());
                SendMessage message = new SendMessage(task.getChatId(), notificationMessage);
                if (executeMessage(message)) {
                    logger.info("Notification sent to chat: {}", task.getChatId());
                }
            }
        } catch (Exception e) {
            logger.error("Error sending scheduled notifications", e);
        }
    }

    private void sendMessage(Long chatId, String text) {
        executeMessage(new SendMessage(chatId, text));
    }

    private boolean executeMessage(SendMessage message) {
        try {
            SendResponse response = telegramBot.execute(message);
            return response.isOk();
        } catch (Exception e) {
            logger.error("Error sending message", e);
            return false;
        }
    }
}