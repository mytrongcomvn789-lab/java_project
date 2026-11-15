package com.smartdrone.application.orders;

import java.time.Instant;
import java.util.UUID;
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.domain.workflow.OrderEvent;

/** Payload bất biến chỉ phát sau commit; 4.3/4.4 nhận bằng @EventListener.
 * eventId là ID lịch sử, có thể dùng để chống xử lý lặp. Không có địa chỉ/điện thoại.
 */
public record OrderReviewedEvent(UUID eventId, UUID orderId, UUID customerId,
        UUID pickupStationId, UUID dropoffStationId, long version,
        OrderStatus fromStatus, OrderStatus toStatus, OrderAction action, OrderEvent event,
        UUID actorId, RoleName actorRole, String reason, Instant occurredAt) { }
