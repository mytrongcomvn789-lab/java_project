package com.smartdrone.application.statistics.dto;

import java.time.LocalDate;

/** Một ngày địa phương (Asia/Ho_Chi_Minh). Ngày không có đơn vẫn có dòng với mọi số = 0. */
public record DailyOrderStats(LocalDate date, OrderCounts orders) { }
