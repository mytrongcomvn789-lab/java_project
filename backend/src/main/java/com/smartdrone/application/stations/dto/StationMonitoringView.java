package com.smartdrone.application.stations.dto;

import java.util.UUID;

import com.smartdrone.domain.enums.StationStatus;

/** Một dòng giám sát trạm (thứ web sẽ hiển thị trên thẻ trạm). */
public record StationMonitoringView(UUID id, String name, StationStatus status, int capacity,
                                    StationConnection connection, StationPackageLoad packages) { }