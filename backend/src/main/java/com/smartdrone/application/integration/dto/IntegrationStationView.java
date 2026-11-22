package com.smartdrone.application.integration.dto;

import java.util.UUID;

import com.smartdrone.domain.enums.StationStatus;

public record IntegrationStationView(UUID id, String name, double latitude, double longitude,
                                     StationStatus status) { }