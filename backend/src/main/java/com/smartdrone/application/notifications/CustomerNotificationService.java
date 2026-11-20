package com.smartdrone.application.notifications;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.notifications.dto.NotificationPage;
import com.smartdrone.application.notifications.dto.NotificationView;
import com.smartdrone.domain.entity.CustomerNotification;
import com.smartdrone.infrastructure.persistence.CustomerNotificationRepository;

/** Đọc/đánh dấu đã đọc. Mọi truy vấn đều gắn userId của người gọi, không có cách xem thông báo của người khác. */
@Service
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerNotificationService {
    private static final int MAX_PAGE_SIZE = 50;

    private final CustomerNotificationRepository notifications;
    private final Clock clock;

    public CustomerNotificationService(CustomerNotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NotificationPage list(UUID userId, boolean unreadOnly, int page, int pageSize) {
        int p = Math.max(page, 1);
        int size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(p - 1, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<CustomerNotification> result = unreadOnly
                ? notifications.findByUserIdAndReadAtIsNull(userId, pageable)
                : notifications.findByUserId(userId, pageable);
        return new NotificationPage(result.getContent().stream().map(NotificationView::from).toList(),
                p, size, result.getTotalElements(), notifications.countByUserIdAndReadAtIsNull(userId));
    }

    /** Idempotent: đã đọc rồi thì không đổi. Của người khác hoặc không tồn tại: cùng 404. */
    @Transactional
    public void markRead(UUID userId, UUID id) {
        CustomerNotification n = notifications.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        n.markRead(Instant.now(clock));
    }

    @Transactional
    public int markAllRead(UUID userId) {
        return notifications.markAllRead(userId, Instant.now(clock));
    }
}