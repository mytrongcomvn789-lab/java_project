package com.smartdrone.application.realtime;

import java.util.UUID;

import com.smartdrone.domain.enums.OrderStatus;

/**
 * Event envelope dùng chung cho web (React) và mobile (Flutter), xem docs/05-api/realtime-api.md.
 * Cố ý KHÔNG chứa dữ liệu cá nhân (người nhận, điện thoại, địa chỉ, lý do): client cần chi tiết thì gọi REST.
 */
public record OrderRealtimeEvent(
        int schemaVersion,
        UUID eventId,
        String type,
        String event,
        UUID orderId,
        UUID attemptId,
        OrderStatus fromStatus,
        OrderStatus status,
        long version,
        String occurredAt) {   // ISO-8601 UTC, giờ của server

    public static final int SCHEMA_VERSION = 1;
    public static final String TYPE_STATUS_CHANGED = "ORDER_STATUS_CHANGED";

    public static OrderRealtimeEvent from(OrderStatusChanged e) {
        return new OrderRealtimeEvent(SCHEMA_VERSION, e.eventId(), TYPE_STATUS_CHANGED, e.event(),
                e.orderId(), e.attemptId(), e.from(), e.to(), e.version(), e.occurredAt().toString());
    }
}