package com.smartdrone.domain.enums;

/** Phạm vi (scope) mà một API key có thể được cấp, ứng với đúng một endpoint dưới /v1/integration/**. */
public enum IntegrationScope {
    ORDERS_READ,      // GET /v1/integration/orders/{id}/status
    STATIONS_READ     // GET /v1/integration/stations
}