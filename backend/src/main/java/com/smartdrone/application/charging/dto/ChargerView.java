package com.smartdrone.application.charging.dto;

import java.time.Instant;
import java.util.UUID;

import com.smartdrone.domain.enums.ChargerState;

/** Một thiết bị sạc và drone đang cắm (nếu có). Các số liệu sạc là giả lập theo công thức. */
public record ChargerView(UUID id, String code, ChargerState state, boolean online, Instant lastHeartbeatAt,
                          DroneRef drone, Integer batteryPercent, Integer startPercent, Integer targetPercent,
                          Long chargedMinutes, Long remainingMinutes, Instant readyAt, Double temperatureC,
                          boolean canRelease) { }
