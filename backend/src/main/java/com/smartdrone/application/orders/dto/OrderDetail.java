package com.smartdrone.application.orders.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty; // giữ import giống OrderView hiện có trong repo
import com.smartdrone.application.packages.dto.PackageView;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.domain.enums.OrderStatus;

public record OrderDetail(UUID id, long version, OrderStatus status, String statusLabel,
                          UUID pickupStationId, UUID dropoffStationId,
                          RecipientView recipient, @JsonProperty("package") PackageView packageInfo,
                          List<StatusHistoryView> history, Instant createdAt, Instant updatedAt) {
    public static OrderDetail from(Order o, PackageView pkg, List<StatusHistoryView> history) {
        return new OrderDetail(o.getId(), o.getVersion(), o.getStatus(), o.getStatus().label(),
                o.getPickupStationId(), o.getDropoffStationId(), RecipientView.from(o.getRecipient()),
                pkg, history, o.getCreatedAt(), o.getUpdatedAt());
    }
}