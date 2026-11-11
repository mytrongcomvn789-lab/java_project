
package com.smartdrone.application.stations.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.enums.StationStatus;

public record StationResponse(
    UUID id,
    String name,
    BigDecimal latitude,
    BigDecimal longitude,
    Integer capacity,
    StationStatus status,
    Instant createdAt,
    Instant updatedAt
) {

    public static StationResponse from(Station station) {
        return new StationResponse(
            station.getId(),
            station.getName(),
            station.getLatitude(),
            station.getLongitude(),
            station.getCapacity(),
            station.getStatus(),
            station.getCreatedAt(),
            station.getUpdatedAt()
        );
    }
}
