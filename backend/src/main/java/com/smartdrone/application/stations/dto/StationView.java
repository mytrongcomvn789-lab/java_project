package com.smartdrone.application.stations.dto;

import java.util.UUID;

import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.enums.StationStatus;

public record StationView(UUID id, String name, String address, int capacity, StationStatus status) {
    public static StationView from(Station station) {
        return new StationView(station.getId(), station.getName(), station.getAddress(),
                station.getCapacity(), station.getStatus());
    }
}
