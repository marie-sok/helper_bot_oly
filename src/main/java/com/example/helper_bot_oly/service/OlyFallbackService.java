package com.example.helper_bot_oly.service;

import com.example.helper_bot_oly.entity.HelperTask;
import com.example.helper_bot_oly.repository.HelperTaskRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OlyFallbackService {

    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private static final Pattern RELATIVE_REMINDER_PATTERN = Pattern.compile(
            "(?iu)^напомни(?:\\s+мне)?\\s+(сегодня|завтра)\\s+в\\s+(\\d{1,2}):(\\d{2})\\s+(.+)$"
    );

    private static final Pattern DELETE_REMINDER_PATTERN = Pattern.compile(
            "(?iu)^(?:удали|отмени)\\s+напоминание\\s*#?(\\d+)\\s*$"
    );

    private final HelperTaskRepository helperTaskRepository;

    @Value("${oly.timezone:Europe/Amsterdam}")
    private String timeZone;

    public OlyFallbackService(HelperTaskRepository helperTaskRepository) {
        this.helperTaskRepository = helperTaskRepository;
    }

    public String reply(Long chatId, String userText) {
        if (userText == null || userText.isBlank()) {
            return "Напиши мне что-нибудь текстом 🐱";
        }

        String text = userText.trim();
        String lower = text.toLowerCase(Locale.ROOT);

        Matcher deleteMatcher = DELETE_REMINDER_PATTERN.matcher(text);
        if (deleteMatcher.matches()) {
            return deleteReminder(chatId, Long.parseLong(deleteMatcher.group(1)));
        }

        Matcher reminderMatcher = RELATIVE_REMINDER_PATTERN.matcher(text);
        if (reminderMatcher.matches()) {
            return createRelativeReminder(chatId, reminderMatcher);
        }

        if (lower.contains("напомин") && (
                lower.contains("какие") ||
                lower.contains("покажи") ||
                lower.contains("список") ||
                lower.contains("мои")
        )) {
            return listReminders(chatId);
        }

        if (lower.matches("^(привет|приветик|здравствуй|здравствуйте|hello|hi)[!,. ]*$")) {
            return "Привет 🐱 Я Oly. Я на связи: могу вести напоминания и выполнять базовые команды даже без AI-режима.";
        }

        if (lower.contains("кто ты") || lower.contains("что ты умеешь") || lower.contains("что умеешь")) {
            return "Я Oly 🐱 — Telegram-помощник. Сейчас доступен автономный режим: напоминания, список и удаление напоминаний, /joke, /help. Когда подключён AI-ключ, я также веду диалог, ищу свежую информацию и помогаю с текстами, идеями и планированием.";
        }

        if (lower.equals("спасибо") || lower.equals("спасибо!") || lower.equals("thanks") || lower.equals("thank you")) {
            return "Пожалуйста 🐱";
        }

        return "Я на связи 🐱 Сейчас работаю в автономном режиме без внешнего AI.\n\n" +
                "Можешь написать, например:\n" +
                "• «напомни завтра в 18:00 позвонить маме»\n" +
                "• «покажи мои напоминания»\n" +
                "• «удали напоминание #12»\n" +
                "• /joke\n" +
                "• /help";
    }

    private String createRelativeReminder(Long chatId, Matcher matcher) {
        String dayWord = matcher.group(1).toLowerCase(Locale.ROOT);
        int hour = Integer.parseInt(matcher.group(2));
        int minute = Integer.parseInt(matcher.group(3));
        String reminderText = matcher.group(4).trim();

        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return "Не могу разобрать время. Используй формат HH:mm, например 18:30.";
        }

        LocalDate date = LocalDate.now(zoneId());
        if ("завтра".equals(dayWord)) {
            date = date.plusDays(1);
        }

        LocalDateTime when = LocalDateTime.of(date, LocalTime.of(hour, minute));
        if (!when.isAfter(LocalDateTime.now(zoneId()))) {
            return "Это время уже прошло. Выбери время позже.";
        }

        HelperTask saved = helperTaskRepository.save(new HelperTask(chatId, reminderText, when));
        return "Готово 🐱\nНапоминание #" + saved.getId() + "\n" +
                when.format(DISPLAY_FORMATTER) + " — " + reminderText;
    }

    private String listReminders(Long chatId) {
        List<HelperTask> reminders = helperTaskRepository
                .findAllByChatIdAndNotificationDateTimeAfterOrderByNotificationDateTimeAsc(
                        chatId,
                        LocalDateTime.now(zoneId()).minusMinutes(1)
                );

        if (reminders.isEmpty()) {
            return "У тебя нет будущих напоминаний 🐱";
        }

        StringBuilder result = new StringBuilder("Твои напоминания 🐱\n");
        for (HelperTask task : reminders) {
            result.append("#")
                    .append(task.getId())
                    .append(" — ")
                    .append(task.getNotificationDateTime().format(DISPLAY_FORMATTER))
                    .append(" — ")
                    .append(task.getMessageText())
                    .append("\n");
        }
        return result.toString().trim();
    }

    private String deleteReminder(Long chatId, long reminderId) {
        return helperTaskRepository.findByIdAndChatId(reminderId, chatId)
                .map(task -> {
                    helperTaskRepository.delete(task);
                    return "Удалено 🐱 Напоминание #" + reminderId + ": " + task.getMessageText();
                })
                .orElse("Не нашла напоминание #" + reminderId + " в этом чате.");
    }

    private ZoneId zoneId() {
        try {
            return ZoneId.of(timeZone);
        } catch (Exception ignored) {
            return ZoneId.of("Europe/Amsterdam");
        }
    }
}
