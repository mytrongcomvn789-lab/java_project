package com.smartdrone.application.notifications;

import java.util.UUID;

import com.smartdrone.application.notifications.dto.NotificationView;

/**
 * Điểm mở rộng cho email. Mặc định KHÔNG có bean nào cài interface này nên không gửi email.
 * Khi nhóm chốt nhà cung cấp: tạo một @Component implements NotificationEmailSender (tra email theo userId,
 * gửi, tự xử lý lỗi). Lỗi ném ra từ đây chỉ bị log, không ảnh hưởng realtime hay nghiệp vụ.
 */
public interface NotificationEmailSender {
    void send(UUID userId, NotificationView notification);
}