package com.smartdrone.domain.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** Thiết bị sạc của trạm. Mỗi thiết bị chỉ nhận một drone tại một thời điểm. */
@Entity
@Table(name = "charging_devices",
        indexes = @Index(name = "ix_charging_devices_station", columnList = "station_id"))
public class ChargingDevice {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // UUID thường (không khóa ngoại) để xóa trạm không bị chặn.
    @Column(name = "station_id", nullable = false)
    private UUID stationId;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false)
    private boolean maintenance;

    @Column(name = "drone_id")
    private UUID droneId;

    @Column(name = "charge_started_at")
    private Instant chargeStartedAt;

    @Column(name = "start_percent")
    private Integer startPercent;

    @Column(name = "target_percent", nullable = false)
    private int targetPercent = 100;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    protected ChargingDevice() { }

    public ChargingDevice(UUID stationId, String code) {
        this.stationId = stationId;
        this.code = code;
    }

    public void plug(UUID droneId, int startPercent, Instant startedAt, int targetPercent) {
        this.droneId = droneId;
        this.startPercent = startPercent;
        this.chargeStartedAt = startedAt;
        this.targetPercent = targetPercent;
    }

    public void unplug() {
        this.droneId = null;
        this.startPercent = null;
        this.chargeStartedAt = null;
    }

    public void setMaintenance(boolean maintenance) { this.maintenance = maintenance; }
    public void heartbeat(Instant at) { this.lastHeartbeatAt = at; }

    public UUID getId() { return id; }
    public UUID getStationId() { return stationId; }
    public String getCode() { return code; }
    public boolean isMaintenance() { return maintenance; }
    public UUID getDroneId() { return droneId; }
    public Instant getChargeStartedAt() { return chargeStartedAt; }
    public Integer getStartPercent() { return startPercent; }
    public int getTargetPercent() { return targetPercent; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
}
