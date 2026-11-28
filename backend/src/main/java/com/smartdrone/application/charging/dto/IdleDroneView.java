package com.smartdrone.application.charging.dto;

import java.util.UUID;

/** Drone đang đỗ tại trạm, chưa cắm vào thiết bị sạc nào. */
public record IdleDroneView(UUID id, String code, int batteryPercent) { }
