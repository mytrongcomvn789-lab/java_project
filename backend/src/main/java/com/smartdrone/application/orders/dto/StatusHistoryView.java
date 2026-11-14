package com.smartdrone.application.orders.dto;

import java.time.Instant;
import com.smartdrone.domain.entity.OrderStatusHistory;
import com.smartdrone.domain.enums.*;

public record StatusHistoryView(long orderVersion, OrderStatus fromStatus, OrderStatus toStatus,
                                OrderAction action, RoleName actorRole, ChangeSource source,
                                String reason, Instant occurredAt) {
    public static StatusHistoryView from(OrderStatusHistory h) {
        return new StatusHistoryView(h.getOrderVersion(), h.getFromStatus(), h.getToStatus(),
                h.getAction(), h.getActorRole(), h.getSource(), h.getReason(), h.getOccurredAt());
    }
}