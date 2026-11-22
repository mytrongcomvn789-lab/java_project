package com.smartdrone.application.statistics.dto;

import java.time.Instant;
import java.util.UUID;

import com.smartdrone.domain.enums.StationStatus;

/**
 * Tải một trạm trong một khung giờ. capacity = sức chứa tối đa; booked = chỗ đã đặt (lịch giao ACTIVE);
 * processing = số kiện đang xử lý tại trạm (PREPARING ở trạm gửi + AWAITING_PICKUP ở trạm nhận).
 */
public record StationLoadStat(UUID stationId, String name, StationStatus status, int capacity,
                              Instant slotStart, Instant slotEnd, long booked, long free,
                              long processing, Double utilization) { }
