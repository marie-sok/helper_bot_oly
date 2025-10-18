package com.example.helper_bot_oly.service;

import com.example.helper_bot_oly.entity.HelperTask;
import com.example.helper_bot_oly.repository.HelperTaskRepository;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TelegramBotUpdatesListener implements UpdatesListener {

    private final Logger logger = LoggerFactory.getLogger(TelegramBotUpdatesListener.class);

    private final Pattern pattern = Pattern.compile("(\\d{2}\\.\\d{2}\\.\\d{4}\\s\\d{2}:\\d{2})(\\s+)(.+)");
    private final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

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
            updates.forEach(update -> {
                logger.info("Processing update: {}", update);
                if (update.message() != null && update.message().text() != null) {
                    String messageText = update.message().text();
                    Long chatId = update.message().chat().id();

                    if ("/start".equals(messageText)) {
                        sendWelcomeMessage(chatId);
                    } else {
                        processNotificationMessage(chatId, messageText);
                    }
                }
            });
        } catch (Exception e) {
            logger.error("Error processing updates", e);
        }
        return UpdatesListener.CONFIRMED_UPDATES_ALL;
    }

    private void sendWelcomeMessage(Long chatId) {
        String welcomeText = """
          Hello, Marie 🐱
          can I help you today ?
           we can be to plan a your day 
           or just small talk about your study
           He-he
                """;

        SendMessage message = new SendMessage(chatId, welcomeText);
        executeMessage(message);
    }

    private void processNotificationMessage(Long chatId, String messageText) {
        Matcher matcher = pattern.matcher(messageText);

        if (matcher.matches()) {
            try {
                String dateTimeString = matcher.group(1);
                String notificationText = matcher.group(3);

                LocalDateTime notificationDateTime = LocalDateTime.parse(
                        dateTimeString, dateTimeFormatter
                );

                if (notificationDateTime.isBefore(LocalDateTime.now())) {
                    sendMessage(chatId, "Unable to set a reminder for a past date");
                    return;
                }

                HelperTask task = new HelperTask(
                        chatId, notificationText, notificationDateTime
                );

                helperTaskRepository.save(task);

                String response = String.format(
                        "The reminder will work %s:\n%s",
                        dateTimeString, notificationText
                );
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
                    """);
        }
    }

    @Scheduled(cron = "0 * * * * *")
    public void sendScheduledNotifications() {
        try {
            LocalDateTime currentDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
            logger.info("Checking notifications for: {}", currentDateTime);

            List<HelperTask> tasks = helperTaskRepository
                    .findAllByNotificationDateTime(currentDateTime);

            for (HelperTask task : tasks) {
                String notificationMessage = String.format(
                        "Remind:\n%s",
                        task.getMessageText()
                );

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
        SendMessage message = new SendMessage(chatId, text);
        executeMessage(message);
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