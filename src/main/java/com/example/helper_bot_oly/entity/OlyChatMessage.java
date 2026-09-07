package com.example.helper_bot_oly.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "oly_chat_message", indexes = {
        @Index(name = "idx_oly_chat_message_chat_id_id", columnList = "chat_id,id")
})
public class OlyChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "role", nullable = false, length = 32)
    private String role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected OlyChatMessage() {
    }

    public OlyChatMessage(Long chatId, String role, String content) {
        this.chatId = chatId;
        this.role = role;
        this.content = content;
        this.createdAt = LocalDateTime.now();
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getChatId() {
        return chatId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
