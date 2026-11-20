package com.smartdrone.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.smartdrone.domain.entity.CustomerNotification;

public interface CustomerNotificationRepository extends JpaRepository<CustomerNotification, UUID> {

    boolean existsByEventId(UUID eventId);

    Page<CustomerNotification> findByUserId(UUID userId, Pageable pageable);

    Page<CustomerNotification> findByUserIdAndReadAtIsNull(UUID userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(UUID userId);

    // Luôn lọc theo userId: thông báo của người khác và thông báo không tồn tại cho cùng kết quả (không thấy).
    Optional<CustomerNotification> findByIdAndUserId(UUID id, UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CustomerNotification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") UUID userId, @Param("now") Instant now);
}