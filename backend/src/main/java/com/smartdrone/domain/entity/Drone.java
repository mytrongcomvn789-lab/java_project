package com.smartdrone.domain.entity;

import java.util.UUID;

import com.smartdrone.domain.enums.DroneStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Drone dùng chung giữa các trạm (không cố định ở trạm nào). batteryPercent là mức pin lúc không sạc;
 * khi đang cắm sạc, mức pin hiện tại được tính từ thiết bị sạc.
 */
@Entity
@Table(name = "drones")
public class Drone {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "battery_percent", nullable = false)
    private int batteryPercent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DroneStatus status;

    // UUID thường (không khóa ngoại) để xóa trạm không bị chặn; null khi đang bay.
    @Column(name = "station_id")
    private UUID stationId;

    protected Drone() { }

    public Drone(String code, int batteryPercent, DroneStatus status, UUID stationId) {
        this.code = code;
        this.batteryPercent = batteryPercent;
        this.status = status;
        this.stationId = stationId;
    }

    public void park(int batteryPercent) {
        this.batteryPercent = batteryPercent;
        this.status = DroneStatus.IDLE;
    }

    /** Cất cánh: không còn thuộc trạm nào cho tới khi hạ cánh. */
    public void takeOff(int batteryPercent) {
        this.batteryPercent = batteryPercent;
        this.status = DroneStatus.IN_FLIGHT;
        this.stationId = null;
    }

    public void land(UUID stationId, int batteryPercent) {
        this.stationId = stationId;
        this.batteryPercent = batteryPercent;
        this.status = DroneStatus.IDLE;
    }

    public void startCharging() {
        this.status = DroneStatus.CHARGING;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public int getBatteryPercent() { return batteryPercent; }
    public DroneStatus getStatus() { return status; }
    public UUID getStationId() { return stationId; }
}
