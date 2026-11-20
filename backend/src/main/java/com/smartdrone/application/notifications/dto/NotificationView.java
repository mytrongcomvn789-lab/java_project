package com.smartdrone.application.notifications.dto;

import java.util.UUID;

import com.smartdrone.domain.entity.CustomerNotification;
import com.smartdrone.domain.enums.OrderStatus;

/** Một thông báo; dùng chung cho REST và socket, web và mobile. Thời gian là chuỗi ISO-8601 UTC. */
public record NotificationView(UUID id, UUID eventId, String type, String event, UUID orderId,
                               OrderStatus status, String title, String body, String detailPath,
                               String createdAt, String readAt) {

    public static final String TYPE_STATUS_CHANGED = "ORDER_STATUS_CHANGED";

    public static NotificationView from(CustomerNotification n) {
        return new NotificationView(n.getId(), n.getEventId(), TYPE_STATUS_CHANGED, n.getEventName(),
                n.getOrderId(), n.getStatus(), n.getTitle(), n.getBody(),
                "/v1/orders/" + n.getOrderId(),               // REST chi tiết đơn, client tự đổi sang route của mình
                n.getCreatedAt().toString(),
                n.getReadAt() == null ? null : n.getReadAt().toString());
    }
}