package com.smartdrone.application.scheduling;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.smartdrone.domain.enums.StationStatus;

/** Chỗ còn trống của một trạm theo từng slot trong một ngày (web dùng để chọn slot). */
public record StationAvailabilityView(
        UUID stationId,
        String stationName,
        StationStatus stationStatus,
        int capacity,
        int slotMinutes,
        LocalDate date,
        String timeZone,
        List<Slot> slots) {

    /** used = số lịch đang giữ chỗ; bookable = còn chỗ, trạm hoạt động và nằm trong thời gian cho phép đặt. */
    public record Slot(Instant slotStart, Instant slotEnd, int used, int free, boolean bookable) { }
}