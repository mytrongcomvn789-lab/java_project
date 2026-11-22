package com.smartdrone.application.statistics.dto;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.smartdrone.application.statistics.DateBasis;
import com.smartdrone.domain.enums.OrderStatus;

public record OrderStatsSummary(LocalDate from, LocalDate to, String timezone, DateBasis dateBasis,
                                UUID stationId, OrderCounts orders, Map<OrderStatus, Long> byStatus,
                                OrderRates rates, AttemptStats attempts, DeliveryTimeStats deliveryTime) { }
