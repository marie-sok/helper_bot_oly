package com.example.helper_bot_oly.repository;

import com.example.helper_bot_oly.entity.OlyChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OlyChatMessageRepository extends JpaRepository<OlyChatMessage, Long> {
    List<OlyChatMessage> findTop30ByChatIdOrderByIdDesc(Long chatId);
    void deleteByChatId(Long chatId);
}
