package com.smartdrone.application.orders.dto;

import java.time.Instant;
import java.util.UUID;
import com.smartdrone.application.packages.dto.PackageView;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.domain.enums.OrderStatus;

public record OrderSummary(UUID id, OrderStatus status, String statusLabel,
                           UUID pickupStationId, UUID dropoffStationId,
                           String recipientName, String recipientAddressLine,
                           String packageDescription, Integer packageWeightGrams,
                           Instant createdAt, Instant updatedAt) {
    public static OrderSummary from(Order o, PackageView pkg) {
        return new OrderSummary(o.getId(), o.getStatus(), o.getStatus().label(),
                o.getPickupStationId(), o.getDropoffStationId(),
                o.getRecipient().getName(), o.getRecipient().getAddressLine(),
                pkg == null ? null : pkg.description(), pkg == null ? null : pkg.weightGrams(),
                o.getCreatedAt(), o.getUpdatedAt());
    }
}