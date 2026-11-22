package com.smartdrone.application.statistics.dto;

/** Tỷ lệ trong [0, 1]; null khi mẫu bằng 0 (UI hiển thị "chưa có dữ liệu"). */
public record OrderRates(Double successRate, Double returnRate, Double cancelRate, long deliverySample) { }
