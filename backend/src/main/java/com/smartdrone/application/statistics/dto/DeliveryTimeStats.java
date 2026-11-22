package com.smartdrone.application.statistics.dto;

/** Thời gian giao = DISPATCH cuối cùng -> CONFIRM, đơn vị giây. excludedOrders: đơn đã giao nhưng thiếu mốc. */
public record DeliveryTimeStats(long count, long excludedOrders, Double avgSeconds,
                                Long minSeconds, Long maxSeconds) { }
