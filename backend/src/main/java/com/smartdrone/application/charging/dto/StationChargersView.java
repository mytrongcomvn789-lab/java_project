package com.smartdrone.application.charging.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.smartdrone.domain.enums.StationStatus;

/**
 * Chi tiết thiết bị sạc và drone của một trạm (chỉ nhân viên trạm được phân công).
 * nextSlotAt = lúc dự kiến có chỗ nhận drone tiếp theo: null nếu đang còn thiết bị trống.
 * source luôn là SIMULATED vì chưa có thiết bị thật.
 */
public record StationChargersView(UUID stationId, String stationName, StationStatus stationStatus,
                                  ChargerSummary summary, List<ChargerView> chargers,
                                  List<IdleDroneView> idleDrones, Instant nextSlotAt, String source,
                                  Instant generatedAt) { }
