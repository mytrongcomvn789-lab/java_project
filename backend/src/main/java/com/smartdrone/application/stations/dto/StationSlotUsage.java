package com.smartdrone.application.stations.dto;

import java.time.Instant;

/** Số chỗ đang được giữ ở trạm trong khung giờ hiện tại (nguồn: lịch giao của 4.2). */
public record StationSlotUsage(Instant start, Instant end, long used, long free) { }