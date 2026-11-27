package com.smartdrone.application.charging.dto;

import com.smartdrone.domain.enums.ChargerState;

/** Biểu tượng thiết bị sạc ở trang giám sát: chỉ trạng thái và % pin, không lộ chi tiết drone. */
public record ChargerIcon(String code, ChargerState state, Integer batteryPercent) { }
