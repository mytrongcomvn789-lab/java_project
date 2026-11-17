package com.smartdrone.application.orders.dto;

import java.time.Instant;
import java.util.UUID;
import com.smartdrone.domain.entity.DeliveryAttempt;

public record ReceiptContext(OrderDetail order, String senderName, UUID packageId, Attempt attempt) {
    public record Attempt(UUID id, int attemptNo, Instant slotStart, Instant slotEnd) {
        public static Attempt from(DeliveryAttempt a) {
            return new Attempt(a.getId(), a.getAttemptNo(), a.getSlotStart(), a.getSlotEnd());
        }
    }
}
