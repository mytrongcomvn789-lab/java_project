package com.smartdrone.application.dispatch.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bảng điều phối cho nhân viên: đội drone và các đơn đang cần/đã có drone. */
public record DispatchBoardView(List<FleetDrone> fleet, List<Row> orders, String source, Instant generatedAt) {
    public record FleetDrone(UUID id, String code, String status, int batteryPercent, String stationName) { }

    public record Row(UUID orderId, String orderStatus, String pickupStationName, String dropoffStationName,
                      DispatchView dispatch) { }
}
