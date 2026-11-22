package com.smartdrone.application.statistics.dto;

/** Số lần giao (attempt), đếm riêng với số đơn. retryRate = ordersWithRetry / ordersWithAttempt. */
public record AttemptStats(long totalAttempts, long retryAttempts, long ordersWithRetry,
                           long ordersWithAttempt, long activeAttempts, long releasedAttempts,
                           Double retryRate) { }
