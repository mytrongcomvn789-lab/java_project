package com.smartdrone.application.orders.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;

/** Body của POST /v1/orders/{id}/schedule. slotStart là thời điểm UTC, ví dụ 2026-11-16T03:00:00Z. */
public record ScheduleOrderRequest(
        @NotNull(message = "Thiếu slotStart") Instant slotStart) { }