package com.example.helper_bot_oly.service;

import com.example.helper_bot_oly.entity.OlyKnowledgeItem;
import com.example.helper_bot_oly.repository.OlyKnowledgeItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class GruGithubWatchService {

    private static final Logger logger = LoggerFactory.getLogger(GruGithubWatchService.class);
    private static final String WATCH_KIND = "GITHUB_WATCH";
    private static final String GRU_REPOSITORY = "marie-sok/gru.";

    private final ObjectMapper objectMapper;
    private final OlyKnowledgeItemRepository knowledgeRepository;
    private final TelegramBot telegramBot;

    @Value("${oly.github.webhook-secret:${GITHUB_WEBHOOK_SECRET:}}")
    private String webhookSecret;

    @Value("${oly.owner-chat-id:${OLY_OWNER_CHAT_ID:}}")
    private String ownerChatId;

    public GruGithubWatchService(
            ObjectMapper objectMapper,
            OlyKnowledgeItemRepository knowledgeRepository,
            TelegramBot telegramBot
    ) {
        this.objectMapper = objectMapper;
        this.knowledgeRepository = knowledgeRepository;
        this.telegramBot = telegramBot;
    }

    public boolean isWebhookConfigured() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }

    public boolean isOwnerConfigured() {
        return parseOwnerChatId() != null;
    }

    public boolean isOwnerChat(Long chatId) {
        Long owner = parseOwnerChatId();
        return owner != null && chatId != null && owner.equals(chatId);
    }

    public boolean verifySignature(byte[] body, String signatureHeader) {
        if (!isWebhookConfigured() || body == null || signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.trim().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            logger.error("Unable to verify GitHub webhook signature", e);
            return false;
        }
    }

    public String subscribe(Long chatId) {
        if (!isOwnerChat(chatId)) {
            logger.warn("Rejected GRU Guardian subscription from non-owner chat");
            return "Команда недоступна.";
        }

        OlyKnowledgeItem item = knowledgeRepository
                .findFirstByChatIdAndKindAndKeyNameIgnoreCase(chatId, WATCH_KIND, GRU_REPOSITORY)
                .orElseGet(() -> new OlyKnowledgeItem(
                        chatId,
                        WATCH_KIND,
                        GRU_REPOSITORY,
                        "GRU Guardian",
                        "Watch GitHub events for " + GRU_REPOSITORY
                ));
        item.setCompleted(false);
        item.setContent("Watch GitHub events for " + GRU_REPOSITORY);
        knowledgeRepository.save(item);
        return "✅ GRU Guardian включён.";
    }

    public String unsubscribe(Long chatId) {
        if (!isOwnerChat(chatId)) {
            logger.warn("Rejected GRU Guardian unsubscribe from non-owner chat");
            return "Команда недоступна.";
        }

        return knowledgeRepository
                .findFirstByChatIdAndKindAndKeyNameIgnoreCase(chatId, WATCH_KIND, GRU_REPOSITORY)
                .map(item -> {
                    item.setCompleted(true);
                    knowledgeRepository.save(item);
                    return "GRU Guardian выключен.";
                })
                .orElse("GRU Guardian уже выключен.");
    }

    public int handleEvent(String eventName, String deliveryId, byte[] body) throws Exception {
        JsonNode payload = objectMapper.readTree(body);
        String repository = payload.path("repository").path("full_name").asText("");

        if ("ping".equals(eventName)) {
            logger.info("GitHub webhook ping received: delivery={}, repo={}", deliveryId, repository);
            return 0;
        }

        if (!GRU_REPOSITORY.equalsIgnoreCase(repository)) {
            logger.warn("Ignoring GitHub webhook for unexpected repository: event={}, delivery={}, repo={}", eventName, deliveryId, repository);
            return 0;
        }

        if (!isOwnerConfigured()) {
            logger.warn("GRU Guardian event ignored because OLY_OWNER_CHAT_ID is not configured");
            return 0;
        }

        String alert = buildAlert(eventName, payload);
        if (alert == null || alert.isBlank()) {
            logger.info("GitHub event ignored by GRU Guardian: event={}, delivery={}", eventName, deliveryId);
            return 0;
        }

        List<OlyKnowledgeItem> watchers = knowledgeRepository
                .findAllByKindAndKeyNameIgnoreCaseAndCompletedFalse(WATCH_KIND, GRU_REPOSITORY)
                .stream()
                .filter(item -> isOwnerChat(item.getChatId()))
                .toList();

        int sent = 0;
        for (OlyKnowledgeItem watcher : watchers) {
            if (sendTelegram(watcher.getChatId(), alert)) {
                sent++;
            }
        }

        logger.info("GRU Guardian processed GitHub event: event={}, delivery={}, authorizedWatchers={}, sent={}",
                eventName, deliveryId, watchers.size(), sent);
        return sent;
    }

    private String buildAlert(String eventName, JsonNode payload) {
        return switch (eventName) {
            case "push" -> pushAlert(payload);
            case "pull_request" -> pullRequestAlert(payload);
            case "issues" -> issueAlert(payload);
            case "workflow_run" -> workflowRunAlert(payload);
            case "check_suite" -> checkSuiteAlert(payload);
            default -> null;
        };
    }

    private String pushAlert(JsonNode payload) {
        String ref = payload.path("ref").asText("").replace("refs/heads/", "");
        String actor = firstNonBlank(payload.path("sender").path("login").asText(""), payload.path("pusher").path("name").asText(""));
        JsonNode commits = payload.path("commits");
        int count = commits.isArray() ? commits.size() : 0;
        String message = payload.path("head_commit").path("message").asText("");
        String compare = payload.path("compare").asText("");

        return "🟣 GRU · push\n" +
                "Ветка: " + safe(ref) + "\n" +
                "Автор: " + safe(actor) + "\n" +
                "Коммитов: " + count + "\n" +
                (message.isBlank() ? "" : "Последний: " + oneLine(message, 220) + "\n") +
                (compare.isBlank() ? "" : compare);
    }

    private String pullRequestAlert(JsonNode payload) {
        String action = payload.path("action").asText("");
        JsonNode pr = payload.path("pull_request");
        String title = pr.path("title").asText("");
        int number = payload.path("number").asInt();
        String actor = payload.path("sender").path("login").asText("");
        String url = pr.path("html_url").asText("");
        return "🔵 GRU · Pull Request #" + number + " · " + safe(action) + "\n" +
                oneLine(title, 260) + "\n" +
                "Автор: " + safe(actor) + (url.isBlank() ? "" : "\n" + url);
    }

    private String issueAlert(JsonNode payload) {
        String action = payload.path("action").asText("");
        JsonNode issue = payload.path("issue");
        int number = issue.path("number").asInt();
        String title = issue.path("title").asText("");
        String url = issue.path("html_url").asText("");
        return "🟡 GRU · Issue #" + number + " · " + safe(action) + "\n" +
                oneLine(title, 260) + (url.isBlank() ? "" : "\n" + url);
    }

    private String workflowRunAlert(JsonNode payload) {
        String action = payload.path("action").asText("");
        JsonNode run = payload.path("workflow_run");
        String status = run.path("status").asText("");
        String conclusion = run.path("conclusion").asText("");

        if (!"completed".equalsIgnoreCase(action) && !"completed".equalsIgnoreCase(status)) {
            return null;
        }

        String icon = "success".equalsIgnoreCase(conclusion) ? "✅" : "🔴";
        String name = run.path("name").asText("GitHub Actions");
        String branch = run.path("head_branch").asText("");
        String url = run.path("html_url").asText("");
        return icon + " GRU · GitHub Actions\n" +
                safe(name) + ": " + safe(conclusion) + "\n" +
                (branch.isBlank() ? "" : "Ветка: " + branch + "\n") +
                (url.isBlank() ? "" : url);
    }

    private String checkSuiteAlert(JsonNode payload) {
        String action = payload.path("action").asText("");
        JsonNode suite = payload.path("check_suite");
        String conclusion = suite.path("conclusion").asText("");
        if (!"completed".equalsIgnoreCase(action) || "success".equalsIgnoreCase(conclusion) || conclusion.isBlank()) {
            return null;
        }
        String branch = suite.path("head_branch").asText("");
        return "🔴 GRU · Check suite: " + safe(conclusion) + (branch.isBlank() ? "" : "\nВетка: " + branch);
    }

    private boolean sendTelegram(Long chatId, String text) {
        try {
            SendResponse response = telegramBot.execute(new SendMessage(chatId, text));
            if (!response.isOk()) {
                logger.warn("GRU Guardian Telegram send failed: {}", response.description());
            }
            return response.isOk();
        } catch (Exception e) {
            logger.error("GRU Guardian Telegram send failed", e);
            return false;
        }
    }

    private Long parseOwnerChatId() {
        if (ownerChatId == null || ownerChatId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(ownerChatId.trim());
        } catch (NumberFormatException e) {
            logger.error("OLY_OWNER_CHAT_ID is invalid");
            return null;
        }
    }

    private String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b == null ? "" : b);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String oneLine(String value, int limit) {
        String normalized = value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "…";
    }
}
