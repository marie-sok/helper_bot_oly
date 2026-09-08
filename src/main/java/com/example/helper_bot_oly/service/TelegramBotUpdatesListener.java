package com.example.helper_bot_oly.service;

import com.example.helper_bot_oly.entity.HelperTask;
import com.example.helper_bot_oly.repository.HelperTaskRepository;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Document;
import com.pengrad.telegrambot.model.PhotoSize;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.Video;
import com.pengrad.telegrambot.model.VideoNote;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.request.SendPhoto;
import com.pengrad.telegrambot.response.SendResponse;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
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
    private final OlyFallbackService olyFallbackService;
    private final GruGithubWatchService gruGithubWatchService;
    private final OlyMediaService olyMediaService;

    @Value("${oly.timezone:Europe/Amsterdam}")
    private String timeZone;

    @Value("${telegram.bot.token:}")
    private String telegramToken;

    public TelegramBotUpdatesListener(
            TelegramBot telegramBot,
            HelperTaskRepository helperTaskRepository,
            OlyAiService olyAiService,
            OlyFallbackService olyFallbackService,
            GruGithubWatchService gruGithubWatchService,
            OlyMediaService olyMediaService
    ) {
        this.telegramBot = telegramBot;
        this.helperTaskRepository = helperTaskRepository;
        this.olyAiService = olyAiService;
        this.olyFallbackService = olyFallbackService;
        this.gruGithubWatchService = gruGithubWatchService;
        this.olyMediaService = olyMediaService;
    }

    @PostConstruct
    public void init() {
        if (!isTelegramConfigured()) {
            logger.warn("Oly started in bootstrap mode: TELEGRAM_BOT_TOKEN is not configured yet. HTTP health endpoint remains available.");
            return;
        }

        logger.info(
                "Oly Telegram update processor ready. Transport=webhook, AI enabled={}, model={}, media={}, imageGeneration={}",
                olyAiService.isAvailable(),
                olyAiService.getModel(),
                olyMediaService.isMediaAvailable(),
                olyMediaService.isImageGenerationAvailable()
        );
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
        String caption = update.message().caption();

        PhotoSize[] photos = update.message().photo();
        if (photos != null && photos.length > 0) {
            processPhoto(chatId, photos, caption);
            return;
        }

        Video video = update.message().video();
        if (video != null) {
            processVideo(chatId, video.fileId(), video.fileSize(), video.mimeType(), caption);
            return;
        }

        VideoNote videoNote = update.message().videoNote();
        if (videoNote != null) {
            processVideo(chatId, videoNote.fileId(), videoNote.fileSize(), "video/mp4", caption);
            return;
        }

        Document document = update.message().document();
        if (document != null && isSupportedMediaDocument(document)) {
            processDocumentMedia(chatId, document, caption);
            return;
        }

        String messageText = update.message().text();
        if (messageText == null || messageText.isBlank()) {
            sendMessage(chatId, "Я уже умею текст, фото и видео 🐱 Пришли медиа с подписью-вопросом или просто напиши сообщение.");
            return;
        }

        String text = messageText.trim();
        String command = extractCommand(text);

        switch (command) {
            case "/start" -> sendWelcomeMessage(chatId);
            case "/help" -> sendHelpMessage(chatId);
            case "/reset" -> resetAiConversation(chatId);
            case "/ai" -> sendAiStatus(chatId);
            case "/joke" -> sendJoke(chatId);
            case "/image" -> generateImage(chatId, commandArguments(text));
            case "/watchgru" -> sendMessage(chatId, gruGithubWatchService.subscribe(chatId));
            case "/unwatchgru" -> sendMessage(chatId, gruGithubWatchService.unsubscribe(chatId));
            default -> processUserText(chatId, text);
        }
    }

    private void processPhoto(Long chatId, PhotoSize[] photos, String caption) {
        PhotoSize best = Arrays.stream(photos)
                .filter(photo -> photo != null && photo.fileId() != null)
                .max(Comparator.comparingLong(this::photoScore))
                .orElse(null);

        if (best == null) {
            sendMessage(chatId, "Не смогла получить фото из Telegram 😿");
            return;
        }

        sendLongMessage(chatId, olyMediaService.analyzeTelegramMedia(
                chatId,
                best.fileId(),
                best.fileSize(),
                "image/jpeg",
                OlyMediaService.MediaKind.IMAGE,
                caption
        ));
    }

    private void processVideo(Long chatId, String fileId, Long fileSize, String mimeType, String caption) {
        sendLongMessage(chatId, olyMediaService.analyzeTelegramMedia(
                chatId,
                fileId,
                fileSize,
                mimeType == null || mimeType.isBlank() ? "video/mp4" : mimeType,
                OlyMediaService.MediaKind.VIDEO,
                caption
        ));
    }

    private void processDocumentMedia(Long chatId, Document document, String caption) {
        String mimeType = document.mimeType();
        boolean video = isVideoMime(mimeType) || hasVideoExtension(document.fileName());
        sendLongMessage(chatId, olyMediaService.analyzeTelegramMedia(
                chatId,
                document.fileId(),
                document.fileSize(),
                mimeType,
                video ? OlyMediaService.MediaKind.VIDEO : OlyMediaService.MediaKind.IMAGE,
                caption
        ));
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

        if (isNaturalImageGenerationRequest(text)) {
            generateImage(chatId, text);
            return;
        }

        String response = olyAiService.isAvailable()
                ? olyAiService.reply(chatId, withOlyStyle(text))
                : olyFallbackService.reply(chatId, text);
        sendLongMessage(chatId, response);
    }

    private void generateImage(Long chatId, String prompt) {
        OlyMediaService.ImageGenerationResult result = olyMediaService.generateImage(chatId, prompt);
        if (!result.ok()) {
            sendMessage(chatId, result.message());
            return;
        }

        try {
            SendResponse response = telegramBot.execute(
                    new SendPhoto(chatId, result.bytes()).caption("Готово 🐱")
            );
            if (!response.isOk()) {
                logger.warn("Telegram generated photo send failed: {}", response.description());
                sendMessage(chatId, "Картинку сгенерировала, но Telegram не дал её отправить 😿");
            }
        } catch (Exception e) {
            logger.error("Error sending generated Oly image", e);
            sendMessage(chatId, "Картинку сгенерировала, но не смогла отправить её в Telegram 😿");
        }
    }

    private void resetAiConversation(Long chatId) {
        olyAiService.resetConversation(chatId);
        sendMessage(chatId, "Контекст нашего AI-диалога очищен. Начинаем с чистого листа 🐱");
    }

    private void sendAiStatus(Long chatId) {
        String status = olyAiService.isAvailable() ? "online" : "offline fallback";
        sendMessage(chatId,
                "Oly AI: " + status +
                        "\nModel: " + olyAiService.getModel() +
                        "\nPhoto/video: " + (olyMediaService.isMediaAvailable() ? "online" : "offline") +
                        "\nImage generation: " + (olyMediaService.isImageGenerationAvailable() ? "online" : "offline")
        );
    }

    private void sendWelcomeMessage(Long chatId) {
        sendMessage(chatId, "Привет, чем могу помочь тебе сегодня? Спланируем день или просто поболтаем ?");
    }

    private void sendHelpMessage(Long chatId) {
        String helpText = """
                Я Oly 🐱

                Можно просто писать мне обычным языком. Я умею:
                • поддерживать диалог и помнить контекст;
                • помогать с планированием дня, задачами и идеями;
                • создавать и вести напоминания;
                • хранить заметки, задачи и полезные факты;
                • работать с фото и видео;
                • помогать с текстами, учебой, переводами и объяснениями;
                • генерировать изображения по описанию;
                • считать, смотреть погоду, время и курсы валют.

                Команды:
                /ai — статус AI и мультимедиа
                /image — сгенерировать изображение
                /reset — очистить текущий контекст диалога
                /joke — рассказать шутку
                /help — эта справка
                """;
        sendLongMessage(chatId, helpText);
    }

    private void sendJoke(Long chatId) {
        if (olyAiService.isAvailable()) {
            sendLongMessage(chatId, olyAiService.reply(
                    chatId,
                    withOlyStyle("Расскажи одну короткую смешную шутку. Без длинного вступления.")
            ));
        } else {
            sendMessage(chatId, "Купил мужик шляпу — а она ему как раз! 😼");
        }
    }

    private void sendWhoIsMisbehaving(Long chatId) {
        try {
            ClassPathResource resource = new ClassPathResource("static/who_misbehaving.jpg");
            byte[] imageBytes;
            try (var inputStream = resource.getInputStream()) {
                imageBytes = inputStream.readAllBytes();
            }

            SendResponse response = telegramBot.execute(new SendPhoto(chatId, imageBytes));
            if (!response.isOk()) {
                logger.warn("Telegram photo send failed: {}", response.description());
                sendMessage(chatId, "Не смогла отправить картинку 😿");
            }
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
        if (!isTelegramConfigured()) {
            return;
        }

        LocalDateTime dueAt = now();

        try {
            List<HelperTask> tasks = helperTaskRepository
                    .findAllByNotificationDateTimeLessThanEqualOrderByNotificationDateTimeAsc(dueAt);

            for (HelperTask task : tasks) {
                String notificationMessage = "⏰ Oly напоминает:\n" + task.getMessageText();
                if (executeMessage(new SendMessage(task.getChatId(), notificationMessage))) {
                    helperTaskRepository.delete(task);
                    logger.info("Reminder {} sent", task.getId());
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

    private String commandArguments(String text) {
        if (text == null) return "";
        int space = text.indexOf(' ');
        return space < 0 ? "" : text.substring(space + 1).trim();
    }

    private boolean isNaturalImageGenerationRequest(String text) {
        String lower = text.toLowerCase(Locale.ROOT).trim();
        return lower.startsWith("нарисуй ")
                || lower.startsWith("нарисуй мне ")
                || lower.contains("сгенерируй картинку")
                || lower.contains("сгенерируй изображение")
                || lower.contains("создай картинку")
                || lower.contains("создай изображение")
                || lower.contains("сделай картинку")
                || lower.startsWith("generate image")
                || lower.startsWith("generate an image");
    }

    private String withOlyStyle(String text) {
        return """
                Style preference for Oly in this conversation:
                Speak naturally and like a real Telegram companion, not a corporate compliance bot.
                Slang, sarcasm, playful teasing and profanity are allowed when they fit the user's tone.
                Do not moralize, scold, act prudish, or add preachy disclaimers to harmless or merely edgy requests.
                Keep necessary safety boundaries for genuinely dangerous, exploitative or illegal requests, but make any boundary concise and non-preachy.

                User message:
                %s
                """.formatted(text);
    }

    private long photoScore(PhotoSize photo) {
        if (photo.fileSize() != null) return photo.fileSize();
        long width = photo.width() == null ? 0L : photo.width();
        long height = photo.height() == null ? 0L : photo.height();
        return width * height;
    }

    private boolean isSupportedMediaDocument(Document document) {
        String mime = document.mimeType();
        String fileName = document.fileName();
        return isImageMime(mime)
                || isVideoMime(mime)
                || hasImageExtension(fileName)
                || hasVideoExtension(fileName);
    }

    private boolean isImageMime(String mime) {
        return mime != null && mime.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    private boolean isVideoMime(String mime) {
        return mime != null && mime.toLowerCase(Locale.ROOT).startsWith("video/");
    }

    private boolean hasImageExtension(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif");
    }

    private boolean hasVideoExtension(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".webm")
                || lower.endsWith(".m4v");
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

    private boolean isTelegramConfigured() {
        return telegramToken != null && !telegramToken.isBlank();
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
        if (!isTelegramConfigured()) {
            logger.debug("Telegram send skipped because TELEGRAM_BOT_TOKEN is not configured.");
            return false;
        }

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
