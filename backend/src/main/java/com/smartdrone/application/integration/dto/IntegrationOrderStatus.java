package com.smartdrone.application.integration.dto;

import java.time.Instant;
import java.util.UUID;

import com.smartdrone.domain.enums.OrderStatus;

public record IntegrationOrderStatus(UUID orderId, OrderStatus status, String statusLabel,
                                     boolean terminal, Instant updatedAt) { }