package com.smartdrone.application.realtime;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.smartdrone.application.notifications.dto.NotificationView;

/** Đẩy đúng vào kênh của khách nhận. "Gửi được" không có nghĩa "đã đọc": đọc chỉ ghi qua POST .../read. */
@Component
public class NotificationRealtimePublisher {
    private static final Logger log = LoggerFactory.getLogger(NotificationRealtimePublisher.class);

    private final SimpMessagingTemplate messaging;

    public NotificationRealtimePublisher(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public void publish(UUID userId, NotificationView view) {
        try {
            messaging.convertAndSend(NotificationChannel.destination(userId), NotificationRealtimeEvent.created(view));
        } catch (RuntimeException ex) {
            log.warn("NOTIFICATION_PUBLISH_FAILED user={} event={}", userId, view.eventId(), ex);
        }
    }
}