package com.example.helper_bot_oly.repository;

import com.example.helper_bot_oly.entity.OlyConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OlyConversationRepository extends JpaRepository<OlyConversation, Long> {

    Optional<OlyConversation> findByChatId(Long chatId);
}
