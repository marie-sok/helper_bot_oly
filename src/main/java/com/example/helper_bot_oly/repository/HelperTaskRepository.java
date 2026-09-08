package com.example.helper_bot_oly.repository;

import com.example.helper_bot_oly.entity.HelperTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface HelperTaskRepository extends JpaRepository<HelperTask, Long> {

    List<HelperTask> findAllByNotificationDateTimeLessThanEqualOrderByNotificationDateTimeAsc(
            LocalDateTime notificationDateTime
    );

    List<HelperTask> findAllByChatIdAndNotificationDateTimeAfterOrderByNotificationDateTimeAsc(
            Long chatId,
            LocalDateTime notificationDateTime
    );

    List<HelperTask> findAllByChatIdOrderByNotificationDateTimeAsc(Long chatId);

    Optional<HelperTask> findByIdAndChatId(Long id, Long chatId);
}
