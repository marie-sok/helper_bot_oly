package com.example.helper_bot_oly.repository;

import com.example.helper_bot_oly.entity.OlyKnowledgeItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OlyKnowledgeItemRepository extends JpaRepository<OlyKnowledgeItem, Long> {
    List<OlyKnowledgeItem> findTop50ByChatIdAndKindOrderByUpdatedAtDesc(Long chatId, String kind);
    List<OlyKnowledgeItem> findTop20ByChatIdAndKindAndContentContainingIgnoreCaseOrderByUpdatedAtDesc(Long chatId, String kind, String query);
    List<OlyKnowledgeItem> findAllByChatIdAndKindAndCompletedOrderByCreatedAtAsc(Long chatId, String kind, boolean completed);
    Optional<OlyKnowledgeItem> findFirstByChatIdAndKindAndKeyNameIgnoreCase(Long chatId, String kind, String keyName);
    Optional<OlyKnowledgeItem> findByIdAndChatId(Long id, Long chatId);
}
