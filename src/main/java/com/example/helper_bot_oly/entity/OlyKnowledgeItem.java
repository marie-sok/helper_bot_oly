package com.example.helper_bot_oly.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "oly_knowledge_item", indexes = {
        @Index(name = "idx_oly_knowledge_chat_kind", columnList = "chat_id,kind"),
        @Index(name = "idx_oly_knowledge_chat_key", columnList = "chat_id,key_name")
})
public class OlyKnowledgeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "key_name", length = 255)
    private String keyName;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "completed", nullable = false)
    private boolean completed;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected OlyKnowledgeItem() {
    }

    public OlyKnowledgeItem(Long chatId, String kind, String keyName, String title, String content) {
        this.chatId = chatId;
        this.kind = kind;
        this.keyName = keyName;
        this.title = title;
        this.content = content;
        this.completed = false;
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getChatId() { return chatId; }
    public String getKind() { return kind; }
    public String getKeyName() { return keyName; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public boolean isCompleted() { return completed; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setKeyName(String keyName) { this.keyName = keyName; }
    public void setTitle(String title) { this.title = title; }
    public void setContent(String content) { this.content = content; }
    public void setCompleted(boolean completed) { this.completed = completed; }
}
