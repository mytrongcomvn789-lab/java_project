package com.smartdrone.application.pickup.dto;

import java.time.Instant;
import java.util.UUID;

import com.smartdrone.domain.entity.DeliveryConfirmation;
import com.smartdrone.domain.enums.ConfirmationMethod;

/** orderVersion là phiên bản đơn SAU khi CONFIRM; confirmedAt là mốc hoàn thành cho 5.1. */
public record DeliveryConfirmationView(UUID id, UUID orderId, long orderVersion, ConfirmationMethod method,
                                       UUID confirmedBy, UUID stationId, UUID photoFileId, String note,
                                       Instant confirmedAt) {
    public static DeliveryConfirmationView from(DeliveryConfirmation c) {
        return new DeliveryConfirmationView(c.getId(), c.getOrderId(), c.getOrderVersion(), c.getMethod(),
                c.getConfirmedBy(), c.getStationId(), c.getPhotoFileId(), c.getNote(), c.getConfirmedAt());
    }
}