package com.smartdrone.application.orders.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartdrone.application.packages.dto.PackageView;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.domain.enums.OrderStatus;

/** Read DTO tạm, cần chốt với Luật (3.7). statusLabel để UI khỏi tự đoán nhãn. */
public record OrderView(
        UUID id,
        long version,                 // dùng làm ETag
        OrderStatus status,
        String statusLabel,
        UUID pickupStationId,
        UUID dropoffStationId,
        RecipientView recipient,
        @JsonProperty("package") PackageView packageInfo,
        Instant createdAt,
        Instant updatedAt) {

    public static OrderView from(Order o, PackageView pkg) {
        return new OrderView(o.getId(), o.getVersion(), o.getStatus(), o.getStatus().label(),
                o.getPickupStationId(), o.getDropoffStationId(),
                RecipientView.from(o.getRecipient()), pkg, o.getCreatedAt(), o.getUpdatedAt());
    }
}