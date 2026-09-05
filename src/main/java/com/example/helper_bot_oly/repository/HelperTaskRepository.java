package com.example.helper_bot_oly.repository;

import com.example.helper_bot_oly.entity.HelperTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface HelperTaskRepository extends JpaRepository<HelperTask, Long> {

    @Query("SELECT h FROM HelperTask h WHERE h.notificationDateTime = :dateTime")
    List<HelperTask> findAllByNotificationDateTime(@Param("dateTime") LocalDateTime dateTime);

    List<HelperTask> findAllByChatIdAndNotificationDateTimeAfterOrderByNotificationDateTimeAsc(
            Long chatId,
            LocalDateTime notificationDateTime
    );

    Optional<HelperTask> findByIdAndChatId(Long id, Long chatId);
}
