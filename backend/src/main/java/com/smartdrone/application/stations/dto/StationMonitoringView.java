package com.smartdrone.application.stations.dto;

import java.util.List;
import java.util.UUID;

import com.smartdrone.application.charging.dto.ChargerIcon;
import com.smartdrone.domain.enums.StationStatus;

/**
 * Một dòng giám sát trạm. capacity = số lịch giao tối đa mỗi khung giờ (nguồn 3.2/4.2);
 * slot = số chỗ đang giữ trong khung giờ hiện tại; packages = số kiện theo vị trí;
 * chargers = biểu tượng các thiết bị sạc (rỗng nếu trạm chưa có thiết bị).
 */
public record StationMonitoringView(UUID id, String name, String address, StationStatus status, int capacity,
                                    StationConnection connection, StationSlotUsage slot,
                                    StationPackageLoad packages, List<ChargerIcon> chargers) { }