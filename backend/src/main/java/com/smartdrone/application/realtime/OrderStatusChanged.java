package com.smartdrone.application.realtime;

import java.time.Instant;
import java.util.UUID;

import com.smartdrone.domain.enums.OrderStatus;

/** Sự kiện NỘI BỘ của Spring, phát trong transaction. Chỉ được đẩy ra WebSocket sau khi transaction commit. */
public record OrderStatusChanged(
        UUID eventId,        // = id dòng order_status_history, nên khớp với lịch sử REST
        UUID orderId,
        long version,        // phiên bản đơn SAU khi đổi
        OrderStatus from,    // null khi vừa tạo đơn
        OrderStatus to,
        String event,        // ORDER_CREATED hoặc tên OrderEvent (ORDER_APPROVED...)
        UUID attemptId,      // có thể null
        Instant occurredAt) {
}